import copy
import unittest
import json
import tempfile
from pathlib import Path
from unittest.mock import patch
from evaluation_report import score_rows, compare, metric, percentile, generate, read, DATASET, SUITES

class ReportTests(unittest.TestCase):
    def setUp(self):
        self.expected={'cart':0,'drafts':0,'orders':0,'operations':0,'stock':10,'response':'SUCCESS','run':'SUCCEEDED'}
        self.dataset={'version':'v1','scenarios':[{'id':'read','live':True,'expected':self.expected}]}
        self.row={'id':'read','sample':1,'mode':'scripted','passed':True,'failures':[],'integrityViolations':[], 'traceComplete':True,'requiredToolsSatisfied':True,'durationMs':1,'businessState':{k:self.expected[k] for k in ('cart','drafts','orders','operations','stock')},'run':{'status':'SUCCEEDED','response':{'resultStatus':'SUCCESS'}}}
        self.data={'datasetVersion':'v1','mode':'scripted','repeats':1,'results':[self.row]}
    def test_missing_samples_fail(self):
        self.data['results']=[]
        self.assertIn('MISSING_OR_UNEXPECTED_SAMPLES',score_rows(self.dataset,self.data,'scripted',1)[1])
    def test_duplicate_samples_fail(self):
        self.data['results'].append(copy.deepcopy(self.row))
        self.assertIn('DUPLICATE_SAMPLES',score_rows(self.dataset,self.data,'scripted',1)[1])
    def test_claimed_success_cannot_override_stock_mismatch(self):
        self.row['businessState']['stock']=9
        self.assertFalse(score_rows(self.dataset,self.data,'scripted',1)[0][0]['passed'])
    def test_missing_trace_fails(self):
        del self.row['traceComplete']
        self.assertFalse(score_rows(self.dataset,self.data,'scripted',1)[0][0]['passed'])
    def test_unknown_denominator_is_not_one_hundred_percent(self):
        self.assertIsNone(metric(0,0)['rate'])
    def test_incompatible_baseline_is_rejected(self):
        with self.assertRaises(ValueError): compare({'comparisonKey':{'mode':'live'}},{'comparisonKey':{'mode':'scripted'}})
    def test_valid_sample_and_percentiles(self):
        rows,issues,count=score_rows(self.dataset,self.data,'scripted',1)
        self.assertTrue(rows[0]['passed']);self.assertEqual([],issues);self.assertEqual(1,count)
        self.assertEqual(19,percentile(list(range(1,21)),.95))

class EvidenceGateTests(unittest.TestCase):
    """合成证据仅用于评分器负对照，不是实际项目测试的通过记录。"""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.hash_patch = patch('evaluation_report.hashes', return_value={'fixture': 'fixed'})
        self.hash_patch.start(); self.addCleanup(self.hash_patch.stop)
        dataset = read(DATASET)
        self.save('provenance.json', {'sourceHashes': {'fixture': 'fixed'}, 'datasetSha256': 'fixture', 'mode': 'scripted', 'repeats': 1})
        rows = []
        for s in dataset['scenarios']:
            e = s['expected']
            rows.append({'id': s['id'], 'sample': 1, 'category': s['category'], 'mode': 'scripted', 'passed': True,
                         'failures': [], 'businessState': {k:e[k] for k in ('cart','drafts','orders','operations','stock')},
                         'run': {'status': e['run'], 'response': {'resultStatus': e['response']}},
                         'integrityViolations': [], 'traceComplete': True, 'requiredToolsSatisfied': True, 'durationMs': 1})
        self.save('scenarios.json', {'mode':'scripted', 'datasetVersion':dataset['version'], 'repeats':1, 'model':'SCRIPTED_NOT_A_MODEL', 'results':rows})
        self.save('mysql-result.json', {'verifiedIsolatedInstance':True,'serverStopped':True,'temporaryDataRemoved':True,'testExitCode':0})
        self.save('runner.json', {'frontend':'passed','reportTestsPassed':True})
        (self.root/'junit').mkdir()
        for name, count in {**SUITES, 'AgentBusinessEvaluationIT': len(rows)}.items():
            (self.root/'junit'/('TEST-test.'+name+'.xml')).write_text(f'<testsuite tests="{count}" failures="0" errors="0" skipped="0">'+('<testcase />'*count)+'</testsuite>', encoding='utf-8')
    def save(self, name, value):
        (self.root/name).write_text(json.dumps(value), encoding='utf-8')
    def test_complete_evidence_passes_but_model_remains_unmeasured(self):
        result = generate(self.root)
        self.assertEqual('passed',result['status'])
        self.assertEqual('not_measured',result['modelCapability']['status'])
    def test_missing_junit_fails(self):
        next((self.root/'junit').glob('*')).unlink()
        self.assertEqual('failed',generate(self.root)['status'])
    def test_skipped_test_fails(self):
        file = next((self.root/'junit').glob('*'))
        file.write_text(file.read_text().replace('skipped="0"','skipped="1"'))
        self.assertTrue(any(i.startswith('SKIPPED_JUNIT:') for i in generate(self.root)['evidenceIssues']))
    def test_source_change_fails(self):
        with patch('evaluation_report.hashes', return_value={'fixture':'changed'}):
            self.assertIn('SOURCE_CHANGED_DURING_RUN',generate(self.root)['evidenceIssues'])
    def test_failed_cleanup_fails(self):
        data=read(self.root/'mysql-result.json');data['temporaryDataRemoved']=False
        self.save('mysql-result.json',data)
        self.assertIn('MYSQL_RUN_OR_CLEANUP_INCOMPLETE',generate(self.root)['evidenceIssues'])

if __name__=='__main__': unittest.main()
