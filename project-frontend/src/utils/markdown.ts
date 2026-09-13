import DOMPurify from 'dompurify'
import { marked } from 'marked'

marked.setOptions({
  gfm: true,
  // 模型回复里的单个换行通常就是换行意图，GitHub 默认会把它合并成空格，这里显式打开
  breaks: true,
})

// 渲染出来的链接一律新开窗口并断开 opener，避免外部页面拿到当前页面的引用
DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node instanceof Element && node.tagName === 'A') {
    node.setAttribute('target', '_blank')
    node.setAttribute('rel', 'noopener noreferrer')
  }
})

/**
 * 把 AI 回复的 Markdown 渲染成可安全插入的 HTML。
 *
 * <p>模型输出属于不可信输入，渲染后必须经 DOMPurify 清洗：只保留聊天场景需要的文本结构，
 * 禁止图片、样式、表单和嵌入内容 —— 它们既能被用来追踪阅读行为，也不是本场景需要的能力。</p>
 */
export function renderMarkdown(source: string): string {
  if (!source) {
    return ''
  }
  const html = marked.parse(source, { async: false }) as string
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['img', 'style', 'iframe', 'form', 'input', 'button', 'video', 'audio', 'svg', 'link', 'meta'],
  })
}
