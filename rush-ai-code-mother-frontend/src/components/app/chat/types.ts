export interface BuildResultView {
  status: 'success' | 'failed'
  stage: string
  summary: string
  report?: string
}

export interface AgentEventView {
  agent: string
  stage: string
  status:
    | 'pending'
    | 'running'
    | 'done'
    | 'failed'
    | 'approval_required'
    | 'approval_approved'
    | 'approval_rejected'
  summary: string
  dagNode?: string
  durationMs?: number
  taskId?: string
  qualityGate?: string
  recoverable?: boolean
  artifact?: Record<string, unknown>
  action?: string
  approvalId?: string
  request?: Record<string, unknown>
}

export interface ChatMessage {
  id: string
  type: 'user' | 'ai'
  content: string
  loading?: boolean
  createTime?: string
  feedback?: 'like' | 'dislike'
  buildResult?: BuildResultView
  agentEvents?: AgentEventView[]
  generationFailed?: boolean
}

export type AiMessageSegment =
  | {
      type: 'markdown'
      content: string
    }
  | {
      type: 'tool-file'
      content: string
      filePath: string
      label: string
    }

export interface FileIconMeta {
  type: string
  label: string
}

export interface FileTreeNode {
  title: string
  key: string
  isLeaf: boolean
  selectable: boolean
  directory: boolean
  iconType: string
  iconLabel: string
  children?: FileTreeNode[]
  raw: API.AppCodeFileTreeVO
}

export type WorkspaceTabKey = 'preview' | 'files' | 'database'
