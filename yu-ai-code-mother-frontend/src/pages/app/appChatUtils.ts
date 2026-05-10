import type { AiMessageSegment, AgentEventView, BuildResultView, ChatMessage, FileIconMeta, FileTreeNode } from '@/components/app/chat/types'

export interface GenerationStreamEvent {
  type: string
  text?: string
  data?: Record<string, any>
}

export const codeLanguageAliasMap: Record<string, string> = {
  html: 'xml',
  vue: 'xml',
  svg: 'xml',
  js: 'javascript',
  jsx: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  css: 'css',
  json: 'json',
  md: 'markdown',
  markdown: 'markdown',
  xml: 'xml',
  yml: 'yaml',
  yaml: 'yaml',
}

export const fileIconMetaMap: Record<string, FileIconMeta> = {
  html: { type: 'html', label: 'HTML' },
  htm: { type: 'html', label: 'HTML' },
  css: { type: 'css', label: 'CSS' },
  scss: { type: 'css', label: 'SCSS' },
  less: { type: 'css', label: 'LESS' },
  js: { type: 'js', label: 'JS' },
  mjs: { type: 'js', label: 'JS' },
  cjs: { type: 'js', label: 'JS' },
  jsx: { type: 'jsx', label: 'JSX' },
  ts: { type: 'ts', label: 'TS' },
  tsx: { type: 'tsx', label: 'TSX' },
  vue: { type: 'vue', label: 'VUE' },
  json: { type: 'json', label: '{}' },
  md: { type: 'md', label: 'MD' },
  markdown: { type: 'md', label: 'MD' },
  svg: { type: 'svg', label: 'SVG' },
  png: { type: 'image', label: 'IMG' },
  jpg: { type: 'image', label: 'IMG' },
  jpeg: { type: 'image', label: 'IMG' },
  gif: { type: 'image', label: 'IMG' },
  webp: { type: 'image', label: 'IMG' },
  ico: { type: 'image', label: 'ICO' },
  xml: { type: 'xml', label: 'XML' },
  yml: { type: 'yaml', label: 'YML' },
  yaml: { type: 'yaml', label: 'YML' },
  txt: { type: 'text', label: 'TXT' },
  env: { type: 'env', label: 'ENV' },
  sql: { type: 'sql', label: 'SQL' },
  go: { type: 'go', label: 'GO' },
  mod: { type: 'go', label: 'MOD' },
  sum: { type: 'go', label: 'SUM' },
  lock: { type: 'lock', label: 'LOCK' },
}

export const frontendEditableExtensions = new Set([
  'html',
  'css',
  'scss',
  'less',
  'js',
  'mjs',
  'cjs',
  'ts',
  'jsx',
  'tsx',
  'vue',
  'json',
  'md',
  'markdown',
  'txt',
  'xml',
  'svg',
  'yml',
  'yaml',
])

export const toolCallBlockPattern = /(?:^|\n)\s*\[工具调用\][^\n]*(?:\n(?!\s*\[(?:工具调用|工具结果|构建结果)\])[\s\S]*?```[\s\S]*?```|(?:\n(?!\s*\[(?:工具调用|工具结果|构建结果)\])[^\n]*){0,3})/g

export const parseBuildResult = (content: string): BuildResultView | undefined => {
  const resultMatch = content.match(/\[构建结果\]\s*(成功|失败)/)
  if (!resultMatch) {
    return undefined
  }
  const stageMatch = content.match(/阶段：([^\n\r]+)/)
  const summaryMatch = content.match(/摘要：([^\n\r]+)/)
  return {
    status: resultMatch[1] === '成功' ? 'success' : 'failed',
    stage: stageMatch?.[1]?.trim() || 'unknown',
    summary: summaryMatch?.[1]?.trim() || (resultMatch[1] === '成功' ? '项目构建成功' : '项目构建失败'),
  }
}

export const parseGenerationStreamEvent = (event: MessageEvent): GenerationStreamEvent | undefined => {
  if (!event.data) {
    return undefined
  }
  try {
    return JSON.parse(event.data) as GenerationStreamEvent
  } catch (error) {
    console.error('解析生成事件失败:', error, event.data)
    return undefined
  }
}

export const normalizeAgentStatus = (status: unknown): AgentEventView['status'] => {
  if (status === 'pending' || status === 'running' || status === 'done' || status === 'failed') {
    return status
  }
  return 'running'
}

export const formatDuration = (durationMs?: number) => {
  if (!durationMs || durationMs <= 0) {
    return ''
  }
  if (durationMs < 1000) {
    return `${durationMs}ms`
  }
  return `${(durationMs / 1000).toFixed(1)}s`
}

export const upsertAgentEvent = (targetMessage: ChatMessage, streamEvent: GenerationStreamEvent) => {
  const data = streamEvent.data || {}
  const durationMs = Number(data.durationMs || 0)
  const agentEvent: AgentEventView = {
    agent: String(data.agent || '智能体'),
    stage: String(data.stage || 'planning'),
    status: normalizeAgentStatus(data.status),
    summary: String(data.summary || streamEvent.text || '正在处理'),
    dagNode: data.dagNode ? String(data.dagNode) : undefined,
    durationMs: Number.isFinite(durationMs) && durationMs > 0 ? durationMs : undefined,
    taskId: data.taskId ? String(data.taskId) : undefined,
    qualityGate: data.qualityGate ? String(data.qualityGate) : undefined,
    recoverable: Boolean(data.recoverable),
  }
  const events = targetMessage.agentEvents ? [...targetMessage.agentEvents] : []
  const existingIndex = events.findIndex((item) => {
    if (agentEvent.dagNode && item.dagNode) {
      return item.dagNode === agentEvent.dagNode && item.status === agentEvent.status
    }
    return item.stage === agentEvent.stage && item.agent === agentEvent.agent && item.status === agentEvent.status
  })
  if (existingIndex >= 0) {
    events.splice(existingIndex, 1, agentEvent)
  } else {
    events.push(agentEvent)
  }
  targetMessage.agentEvents = events
}

export const getGenerationErrorDisplay = (category?: string, rawMessage?: string) => {
  const normalizedCategory = String(category || 'runtime')
  const fallbackMessage = rawMessage || '生成失败'
  switch (normalizedCategory) {
    case 'model_quota':
      return {
        toast: 'AI 模型服务额度不足，请检查账户余额',
        detail: `模型服务问题：${fallbackMessage}`,
      }
    case 'codegen_empty':
      return {
        toast: '生成结束但没有产出项目代码，请重试',
        detail: `代码生成问题：${fallbackMessage}`,
      }
    case 'dependency':
      return {
        toast: '依赖或脚本配置异常，请检查构建诊断',
        detail: `依赖问题：${fallbackMessage}`,
      }
    case 'build':
      return {
        toast: '项目构建失败，请查看构建结果并重试',
        detail: `构建问题：${fallbackMessage}`,
      }
    case 'routing':
      return {
        toast: '路由或预览配置异常，请检查项目结构',
        detail: `路由问题：${fallbackMessage}`,
      }
    case 'permission':
      return {
        toast: '操作权限受限，请调整后重试',
        detail: `权限问题：${fallbackMessage}`,
      }
    default:
      return {
        toast: fallbackMessage,
        detail: `运行问题：${fallbackMessage}`,
      }
  }
}

export const decorateMessageWithBuildResult = (targetMessage: ChatMessage) => {
  const buildResult = parseBuildResult(targetMessage.content)
  if (!buildResult) {
    return buildResult
  }
  targetMessage.buildResult = buildResult
  if (buildResult.status === 'failed') {
    targetMessage.generationFailed = true
  }
  return buildResult
}

export const normalizeFilePath = (filePath: string) => {
  return filePath.replace(/\\/g, '/').replace(/^\/+/, '')
}

export const getFileExtension = (fileName: string) => {
  const normalizedName = fileName.split('/').pop()?.toLowerCase() || ''
  const dotIndex = normalizedName.lastIndexOf('.')
  if (dotIndex <= 0 || dotIndex === normalizedName.length - 1) {
    return normalizedName
  }
  return normalizedName.slice(dotIndex + 1)
}

export const getFileIconMeta = (fileName: string): FileIconMeta => {
  const extension = getFileExtension(fileName)
  return fileIconMetaMap[extension] || { type: 'file', label: extension.slice(0, 3).toUpperCase() || 'TXT' }
}

export const isFrontendEditableFile = (fileName: string) => {
  return frontendEditableExtensions.has(getFileExtension(fileName))
}

export const mapFileTreeNodes = (nodes: API.AppCodeFileTreeVO[] = []): FileTreeNode[] => {
  return nodes.map((node) => {
    const isDirectory = Boolean(node.directory)
    const fileName = node.name || node.path || ''
    const iconMeta = isDirectory ? { type: 'folder', label: '' } : getFileIconMeta(fileName)
    return {
      title: fileName,
      key: node.path || node.name || '',
      isLeaf: !isDirectory,
      selectable: !isDirectory && isFrontendEditableFile(fileName),
      directory: isDirectory,
      iconType: iconMeta.type,
      iconLabel: iconMeta.label,
      children: isDirectory ? mapFileTreeNodes(node.children || []) : undefined,
      raw: node,
    }
  })
}

export const createFileTreeNode = (path: string, name: string, directory: boolean): FileTreeNode => {
  const iconMeta = directory ? { type: 'folder', label: '' } : getFileIconMeta(name)
  return {
    title: name,
    key: path,
    isLeaf: !directory,
    selectable: !directory && isFrontendEditableFile(name),
    directory,
    iconType: iconMeta.type,
    iconLabel: iconMeta.label,
    children: directory ? [] : undefined,
    raw: {
      name,
      path,
      directory,
      children: directory ? [] : undefined,
    },
  }
}

export const collectDirectoryKeys = (nodes: FileTreeNode[]) => {
  const keys: Array<string | number> = []
  const walk = (treeNodes: FileTreeNode[]) => {
    treeNodes.forEach((node) => {
      if (!node.directory) {
        return
      }
      keys.push(node.key)
      if (node.children?.length) {
        walk(node.children)
      }
    })
  }
  walk(nodes)
  return keys
}

export const extractToolCallFilePath = (toolCallContent: string) => {
  const filePathMatch = toolCallContent.match(/["'`]?(?:relativeFilePath|filePath|文件路径|path)["'`]?\s*[:：=]\s*["'`]?([^\s"'`,，。)]+)/i)
  if (filePathMatch?.[1]) {
    return normalizeFilePath(filePathMatch[1])
  }

  const firstLine = toolCallContent.match(/\[工具调用\]\s*([^\n\r]+)/)?.[1]?.trim() || ''
  const displayNamePathMatch = firstLine.match(/^(?:写入文件|修改文件|读取文件|删除文件|读取目录|构建项目)\s+(.+)$/)
  const filePath = displayNamePathMatch?.[1]?.trim().split(/\s+/)[0] || ''
  return normalizeFilePath(filePath)
}

export const getToolCallLabel = (toolCallContent: string) => {
  const toolNameMatch = toolCallContent.match(/["'`]?(?:toolName|工具|操作)["'`]?\s*[:：=]\s*["'`]?(.*?)["'`]?(\s|$)/i)
  const toolName = toolNameMatch?.[1] || ''
  if (toolName === 'modifyFile') {
    return '修改文件'
  }
  if (toolName === 'writeFile') {
    return '写入文件'
  }
  const displayName = toolCallContent.match(/\[工具调用\]\s*([^\s\n\r]+)/)?.[1]
  return displayName || '工具调用'
}

export const getAiMessageSegments = (message: ChatMessage): AiMessageSegment[] => {
  const content = message.content || ''
  if (message.type !== 'ai' || !content.includes('[工具调用]')) {
    return content ? [{ type: 'markdown', content }] : []
  }

  const segments: AiMessageSegment[] = []
  let lastIndex = 0
  for (const match of content.matchAll(toolCallBlockPattern)) {
    const matchText = match[0] || ''
    const matchIndex = match.index ?? 0
    const markdownBefore = content.slice(lastIndex, matchIndex)
    if (markdownBefore.trim()) {
      segments.push({
        type: 'markdown',
        content: markdownBefore,
      })
    }

    const filePath = extractToolCallFilePath(matchText)
    if (filePath) {
      segments.push({
        type: 'tool-file',
        content: matchText,
        filePath,
        label: getToolCallLabel(matchText),
      })
    } else if (matchText.trim()) {
      segments.push({
        type: 'markdown',
        content: matchText,
      })
    }

    lastIndex = matchIndex + matchText.length
  }

  const markdownAfter = content.slice(lastIndex)
  if (markdownAfter.trim()) {
    segments.push({
      type: 'markdown',
      content: markdownAfter,
    })
  }

  return segments.length ? segments : [{ type: 'markdown', content }]
}

export const findCommonPrefixLength = (left: string, right: string) => {
  const maxLength = Math.min(left.length, right.length)
  let index = 0
  while (index < maxLength && left[index] === right[index]) {
    index += 1
  }
  return index
}

export const buildModifiedFilePreview = (currentContent: string, oldContent: string, newContent: string) => {
  if (!oldContent || !currentContent.includes(oldContent)) {
    return {
      initialContent: '',
      targetContent: currentContent,
    }
  }
  const targetContent = currentContent.replace(oldContent, newContent)
  const prefixLength = findCommonPrefixLength(currentContent, targetContent)
  return {
    initialContent: targetContent.slice(0, prefixLength),
    targetContent,
  }
}

export const getStringFromEventData = (data: Record<string, any> | undefined, key: string) => {
  const value = data?.[key]
  return typeof value === 'string' ? value : ''
}

export const appendPreviewCacheBuster = (url: string) => {
  if (!url) {
    return ''
  }
  const separator = url.includes('?') ? '&' : '?'
  return `${url}${separator}t=${Date.now()}`
}

export const extractDeployKey = (url: string) => {
  try {
    const parsedUrl = new URL(url)
    const pathSegments = parsedUrl.pathname.split('/').filter(Boolean)
    return pathSegments[pathSegments.length - 1] || ''
  } catch {
    const pathSegments = url.split('/').filter(Boolean)
    return pathSegments[pathSegments.length - 1] || ''
  }
}

export const isCompileFailureMessage = (errorMessage: string) => {
  return errorMessage.includes('编译') || errorMessage.includes('构建') || errorMessage.includes('回退')
}
