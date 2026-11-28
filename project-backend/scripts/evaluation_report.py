"""Standard-library-only evaluation reporting. Missing evidence fails closed; no LLM-as-judge."""
from __future__ import annotations
import argparse
import hashlib
import json
import math
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

BACKEND = Path(__file__).resolve().parents[1]
DATASET = BACKEND / 'src/test/resources/evaluation/shopping-scenarios.json'
SUITES = {'AiRequestIdentityTest': 14, 'CartRequestRetryTest': 8, 'NativeToolPolicyTest': 2,
          'AiGroundingValidatorTest': 8, 'CartToolGovernanceTest': 1, 'CartOperationMysqlIT': 6,
          'AgentRunMysqlIT': 14, 'AgentRecoveryMysqlIT': 21, 'OrderConfirmationMysqlIT': 23,
          'BusinessIntegrityOracleIT': 7, 'EvaluationModelDriverTest': 4}

def read(path: Path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def hashes():
    files = [BACKEND / 'pom.xml', BACKEND.parent / 'project-frontend/package.json']
    for folder in [BACKEND / 'src', BACKEND / 'scripts', BACKEND.parent / 'project-frontend/src']:
        files.extend(p for p in folder.rglob('*') if p.is_file() and p.suffix in {'.java','.json','.sql','.yml','.ts','.vue','.py','.ps1'})
    return {p.relative_to(BACKEND.parent).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(files))}

def metric(passed, total):
    return {'passed': passed, 'total': total, 'rate': passed / total if total else None}

def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values)*fraction)-1)] if values else None

def score_rows(dataset, data, mode, repeats):
    scenarios = [s for s in dataset['scenarios'] if mode == 'scripted' or s['live']]
    expected = {(s['id'], n) for s in scenarios for n in range(1, repeats+1)}
    definitions = {s['id']: s for s in scenarios}
    rows, issues = data.get('results', []), []
    keys = [(r.get('id'), r.get('sample')) for r in rows]
    if len(keys) != len(set(keys)): issues.append('DUPLICATE_SAMPLES')
    if set(keys) != expected: issues.append('MISSING_OR_UNEXPECTED_SAMPLES')
    if data.get('datasetVersion') != dataset['version'] or data.get('mode') != mode or data.get('repeats') != repeats:
        issues.append('MANIFEST_MISMATCH')
    evaluated, seen = [], set()
    for row in rows:
        key = (row.get('id'), row.get('sample'))
        # 非法/重复样本不能进入分子，防止失败报告出现超过 100% 的比率。
        if key not in expected or key in seen: continue
        seen.add(key)
        failures = list(row.get('failures', []))
        definition = definitions.get(row.get('id'))
        state = row.get('businessState', {})
        if not definition: failures.append('UNKNOWN_SCENARIO')
        else:
            for key in ('cart','drafts','orders','operations','stock'):
                if state.get(key) != definition['expected'][key]: failures.append('BUSINESS_EXPECTATION:'+key)
            run = row.get('run', {})
            response = run.get('response') or {}
            if run.get('status') != definition['expected']['run']: failures.append('RUN_STATUS')
            if response.get('resultStatus', 'NONE') != definition['expected']['response']: failures.append('RESPONSE_STATUS')
        if row.get('integrityViolations') != []: failures.append('BUSINESS_INTEGRITY')
        if row.get('traceComplete') is not True: failures.append('TRACE_INCOMPLETE')
        if row.get('requiredToolsSatisfied') is not True: failures.append('REQUIRED_TOOLS_MISSING')
        if row.get('mode') != mode: failures.append('DRIVER_MODE_MISMATCH')
        if row.get('passed') is not True: failures.append('RUNNER_REJECTED')
        if not isinstance(row.get('durationMs'), (int,float)) or row['durationMs'] < 0: failures.append('INVALID_DURATION')
        evaluated.append({**row, 'passed': not failures, 'failures': sorted(set(failures))})
    return evaluated, issues, len(expected)

def compare(current, baseline):
    if current.get('comparisonKey') != baseline.get('comparisonKey'):
        raise ValueError('Baseline incompatible: dataset, fixture, driver, model, repetitions or metric version differs')
    if baseline.get('evidenceComplete') is not True:
        raise ValueError('Baseline has incomplete evidence')
    metrics = ['scenarioAcceptance','businessIntegrity','traceCompleteness','requiredTools']
    deltas = {k: current['metrics'][k]['rate']-baseline['metrics'][k]['rate'] for k in metrics}
    return {'baselineRun':baseline['runId'],'rateDelta':deltas,
            'p95DurationDeltaMs': current['durationMs']['p95']-baseline['durationMs']['p95']}

def generate(root: Path, baseline_path: Path | None = None):
    manifest = read(root/'provenance.json')
    dataset = read(DATASET)
    issues = []
    if hashes() != manifest['sourceHashes']: issues.append('SOURCE_CHANGED_DURING_RUN')
    mode, repeats = manifest['mode'], manifest['repeats']
    data = read(root/'scenarios.json') if (root/'scenarios.json').exists() else {}
    rows, problems, expected = score_rows(dataset,data,mode,repeats); issues.extend(problems)
    if len(rows) != expected: issues.append('SCENARIO_COUNT_MISMATCH')
    suites=[]
    expected_suites = {**SUITES, 'AgentBusinessEvaluationIT': expected}
    for name, count in expected_suites.items():
        paths=list((root/'junit').glob('TEST-*.'+name+'.xml'))
        if len(paths)!=1: issues.append('MISSING_JUNIT:'+name); continue
        xml=ET.parse(paths[0]).getroot()
        suite={k:int(xml.attrib[k]) for k in ('tests','failures','errors','skipped')}
        suite['name']=name
        if suite['tests']!=count or len(xml.findall('testcase'))!=count: issues.append('WRONG_JUNIT_COUNT:'+name)
        if suite['skipped']: issues.append('SKIPPED_JUNIT:'+name)
        suites.append(suite)
    mysql=read(root/'mysql-result.json') if (root/'mysql-result.json').exists() else {}
    if mysql.get('verifiedIsolatedInstance') is not True or mysql.get('serverStopped') is not True or mysql.get('temporaryDataRemoved') is not True or mysql.get('failureMessage') or mysql.get('cleanupError'):
        issues.append('MYSQL_RUN_OR_CLEANUP_INCOMPLETE')
    runner=read(root/'runner.json') if (root/'runner.json').exists() else {}
    if runner.get('frontend') not in ('passed','not_requested'): issues.append('FRONTEND_BUILD_FAILED')
    if runner.get('reportTestsPassed') is not True: issues.append('REPORTER_SELF_TEST_FAILED')
    if runner.get('error'): issues.append('RUNNER_ERROR')
    metrics = {
        'scenarioAcceptance':metric(sum(r['passed'] for r in rows),expected),
        'businessIntegrity':metric(sum(r.get('integrityViolations')==[] for r in rows),expected),
        'traceCompleteness':metric(sum(r.get('traceComplete') is True for r in rows),expected),
        'requiredTools':metric(sum(r.get('requiredToolsSatisfied') is True for r in rows),expected),
    }
    recovery_ids={s['id'] for s in dataset['scenarios'] if s['category']=='recovery' and (mode=='scripted' or s['live'])}
    recovery=[r for r in rows if r['id'] in recovery_ids]
    metrics['recoveryAcceptance']=metric(sum(r['passed'] and r.get('recoveryVerified') is True for r in recovery),len(recovery_ids)*repeats)
    stable=sum(all(r['passed'] for r in rows if r['id']==s['id']) and sum(r['id']==s['id'] for r in rows)==repeats
               for s in dataset['scenarios'] if mode=='scripted' or s['live'])
    metrics['repeatStability']=metric(stable,expected//repeats)
    durations=[r['durationMs'] for r in rows if isinstance(r.get('durationMs'),(int,float))]
    state='passed' if not issues and all(r['passed'] for r in rows) and all(s['failures']==s['errors']==0 for s in suites) and mysql.get('testExitCode')==0 else 'failed'
    # fixture hash prevents comparing different seeded catalogs or assertion semantics as a model improvement.
    fixture_files=['src/test/java/com/example/service/ai/impl/AgentEvaluationFixture.java','src/test/java/com/example/service/ai/impl/BusinessIntegrityOracle.java','src/test/java/com/example/service/ai/impl/OrderConfirmationMysqlIT.java','src/test/java/com/example/service/ai/impl/EvaluationModelDriver.java','src/test/java/com/example/service/ai/impl/AgentBusinessEvaluationIT.java','scripts/evaluation_report.py']
    fixture_hash=hashlib.sha256(b''.join((BACKEND/p).read_bytes() for p in fixture_files)).hexdigest()
    result={'schemaVersion':1,'runId':root.name,'generatedAt':datetime.now(timezone.utc).isoformat(),'status':state,
            'evidenceComplete':not issues,'evidenceIssues':sorted(set(issues)),
            'mode':mode,'datasetVersion':dataset['version'],'model':data.get('model'),'repeats':repeats,
            'comparisonKey':{'metricVersion':1,'datasetSha256':manifest['datasetSha256'],'fixtureSha256':fixture_hash,'mode':mode,'model':data.get('model'),'temperature':data.get('temperature'),'repeats':repeats},
            'metrics':metrics,'durationMs':{'p50':percentile(durations,.5),'p95':percentile(durations,.95),'scope':'scenario including DB setup/checks, not model-only latency'},
            'modelCapability':{'status':'measured' if mode=='live' and not issues else 'not_measured','scenarioAcceptance':metrics['scenarioAcceptance'] if mode=='live' else None},
            'regression':{'tests':sum(s['tests'] for s in suites),'suites':suites},'mysql':mysql,'frontend':runner.get('frontend'),
            'sourceHashes':manifest['sourceHashes'],'environment':runner.get('environment',{}),'cases':rows,
            'limits':['Scripted plans are injected; they do not measure model tool selection or language understanding.',
                      'Live mode uses a bounded chat-completions evaluation driver with production prompts/tools/core, not the production SSE transport.',
                      'Retrieval and conversation memory are fixture substitutes; no Milvus retrieval or long-dialogue evaluation.',
                      'Only isolated seeded MySQL data; no production JWT/real payment/load-capacity claim.',
                      'Acceptance means expected outcomes including safe rejection, not purchase conversion.',
                      'Latency includes fixture and scoring costs; provider token usage is null if missing.']}
    if baseline_path:
        try: result['comparison']=compare(result,read(baseline_path))
        except (ValueError,KeyError,TypeError) as error:
            result['status']='failed';result['evidenceComplete']=False;result['evidenceIssues'].append('INCOMPATIBLE_BASELINE');result['comparison']={'error':str(error)}
    (root/'report.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    labels={'scenarioAcceptance':'场景验收通过率','businessIntegrity':'业务不变量通过率','traceCompleteness':'执行记录完整率','requiredTools':'必要工具覆盖率','recoveryAcceptance':'恢复场景通过率','repeatStability':'重复运行稳定性'}
    lines=['# Agent 工程验证与业务完整性评测', '',f"- 运行：`{root.name}`",f"- 模式：`{mode}`；结果：**{result['status']}**",f"- 场景样本：{len(rows)}/{expected}；后端测试：{result['regression']['tests']}；前端：{result['frontend']}",
           '- 真实模型能力：'+('已运行，见限定场景结果。' if result['modelCapability']['status']=='measured' else '**未测量，不把 scripted 通过率当成模型成功率。**'),'', '| 指标 | 通过 / 总数 | 比率 |','|---|---:|---:|']
    for key,m in metrics.items(): lines.append(f"| {labels[key]} | {m['passed']} / {m['total']} | "+(f"{m['rate']:.1%}" if m['rate'] is not None else '不适用')+' |')
    lines.extend(['',f"场景时延 P50 / P95：{result['durationMs']['p50']} / {result['durationMs']['p95']} ms（含数据库准备与判定，不是模型响应时延）。",'', '## 逐场景结果','','| 场景 | 样本 | 结果 | 不通过原因 |','|---|---:|---|---|'])
    for r in rows: lines.append(f"| {r['id']} | {r['sample']} | {'通过' if r['passed'] else '失败'} | {', '.join(r['failures']) or '—'} |")
    lines.extend(['','## 证据完整性', '',json.dumps(result['evidenceIssues'],ensure_ascii=False),'','## 判定规则','',
                  '场景通过必须同时满足预期数据库状态、终态、回复状态、必要工具及完整 trace；业务不变量包含金额一致、库存守恒、订单与确认草稿绑定和用户隔离。缺失样本、跳过测试、来源变更或清理失败都会使总门禁失败。恢复率仅以恢复场景为分母，无适用样本时为 null。','', '## 范围限制','']+[f'- {limit}' for limit in result['limits']])
    if 'comparison' in result: lines.extend(['','## 基线比较','','```json',json.dumps(result['comparison'],ensure_ascii=False,indent=2),'```'])
    (root/'report.md').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True);parser.add_argument('--prepare',action='store_true');parser.add_argument('--mode',choices=['scripted','live'],default='scripted');parser.add_argument('--repeats',type=int,default=3);parser.add_argument('--baseline',type=Path)
    args=parser.parse_args();root=args.output.resolve()
    if not root.is_relative_to((BACKEND/'target').resolve()): parser.error('output must be inside backend/target')
    root.mkdir(parents=True,exist_ok=True)
    if args.prepare:
        if not 1<=args.repeats<=10: parser.error('repeats must be 1..10')
        (root/'provenance.json').write_text(json.dumps({'startedAt':datetime.now(timezone.utc).isoformat(),'mode':args.mode,'repeats':args.repeats,'datasetSha256':hashlib.sha256(DATASET.read_bytes()).hexdigest(),'sourceHashes':hashes()},indent=2)+'\n',encoding='utf-8');return 0
    try:
        report=generate(root,args.baseline);print(f"Evaluation {report['status']}: {root / 'report.md'}");return 0 if report['status']=='passed' else 1
    except Exception as error:
        # 即使格式损坏，也留下失败报告，不沿用旧的成功结论。
        failure={'status':'failed','evidenceComplete':False,'evidenceIssues':['REPORT_GENERATION_ERROR:'+type(error).__name__]}
        (root/'report.json').write_text(json.dumps(failure,indent=2),encoding='utf-8');print(json.dumps(failure));return 1

if __name__=='__main__': sys.exit(main())
