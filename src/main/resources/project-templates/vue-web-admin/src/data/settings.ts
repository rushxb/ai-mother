// @AI_GENERATED_SLOT
export interface SettingItem { key: string; label: string; value: string; type: 'text'|'select'|'toggle' }

export const settings: SettingItem[] = [
  { key: 'siteName', label: '系统名称', value: '管理后台', type: 'text' },
  { key: 'notifyEmail', label: '通知邮箱', value: 'admin@example.com', type: 'text' },
  { key: 'dataSync', label: '数据同步', value: 'hourly', type: 'select' }
]
