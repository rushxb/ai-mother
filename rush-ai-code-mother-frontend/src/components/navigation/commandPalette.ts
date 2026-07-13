import type { Component } from 'vue'

/**
 * 全局命令面板条目。
 * 组件只负责搜索、键盘导航和选择事件，具体业务行为由接入方处理。
 */
export interface CommandPaletteItem {
  id: string
  group: string
  title: string
  description: string
  keywords?: string[]
  shortcut?: string
  icon?: Component
  disabled?: boolean
}
