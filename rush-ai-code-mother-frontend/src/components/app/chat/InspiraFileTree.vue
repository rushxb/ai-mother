<template>
  <div class="inspira-tree" :class="{ 'is-empty': !visibleNodes.length }">
    <div v-if="visibleNodes.length" class="tree-list">
      <button
        v-for="row in visibleNodes"
        :key="row.node.key"
        type="button"
        class="tree-row"
        :class="{
          selected: row.node.key === selectedKey,
          folder: row.node.directory,
          file: !row.node.directory,
          selectable: row.selectable,
          expanded: row.expanded,
        }"
        :style="getRowStyle(row)"
        :aria-selected="row.node.key === selectedKey"
        :disabled="row.node.directory ? !row.hasChildren : !row.selectable"
        @click="onRowClick(row)"
      >
        <span class="tree-row-indicator" aria-hidden="true">
          <span v-if="row.node.directory" class="tree-caret">
            <CaretDownOutlined v-if="row.expanded" />
            <CaretRightOutlined v-else />
          </span>
          <span v-else class="tree-leaf-bullet" />
        </span>

        <span class="tree-row-icon" :class="row.node.directory ? 'is-folder' : `is-${row.node.iconType || 'file'}`">
          <FolderOpenOutlined v-if="row.node.directory && row.expanded" />
          <FolderOutlined v-else-if="row.node.directory" />
          <span v-else class="tree-file-badge">{{ row.node.iconLabel || 'TXT' }}</span>
        </span>

        <span class="tree-row-name" :title="row.node.title">{{ row.node.title }}</span>
        <span v-if="row.node.directory && row.node.children?.length" class="tree-row-count">{{ row.node.children.length }}</span>
      </button>
    </div>

    <div v-else class="tree-empty">
      <slot name="empty">暂无文件</slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CaretDownOutlined, CaretRightOutlined, FolderOpenOutlined, FolderOutlined } from '@ant-design/icons-vue'
import type { FileTreeNode } from './types'

const props = withDefaults(
  defineProps<{
    nodes: FileTreeNode[]
    selectedKey?: string
    expandedKeys: Array<string | number>
    depthStep?: number
  }>(),
  {
    selectedKey: '',
    depthStep: 18,
  },
)

const emit = defineEmits<{
  select: [selectedKeys: Array<string | number>]
  expand: [expandedKeys: Array<string | number>]
}>()

type VisibleTreeRow = {
  depth: number
  expanded: boolean
  hasChildren: boolean
  node: FileTreeNode
  selectable: boolean
}

const expandedKeySet = computed(() => new Set(props.expandedKeys.map((key) => String(key))))

const visibleNodes = computed<VisibleTreeRow[]>(() => {
  const rows: VisibleTreeRow[] = []

  const walk = (nodes: FileTreeNode[], depth: number) => {
    nodes.forEach((node) => {
      const hasChildren = Boolean(node.directory && node.children?.length)
      const expanded = node.directory ? expandedKeySet.value.has(String(node.key)) : false
      rows.push({
        depth,
        expanded,
        hasChildren,
        node,
        selectable: Boolean(!node.directory && node.selectable),
      })
      if (hasChildren && expanded) {
        walk(node.children || [], depth + 1)
      }
    })
  }

  walk(props.nodes || [], 0)
  return rows
})

const onRowClick = (row: VisibleTreeRow) => {
  if (row.node.directory) {
    if (!row.hasChildren) {
      return
    }
    const current = new Set(props.expandedKeys.map((key) => String(key)))
    const key = String(row.node.key)
    if (current.has(key)) {
      current.delete(key)
    } else {
      current.add(key)
    }
    emit('expand', Array.from(current))
    return
  }

  if (!row.selectable) {
    return
  }
  emit('select', [row.node.key])
}

const getRowStyle = (row: VisibleTreeRow) => ({
  paddingLeft: `${10 + row.depth * props.depthStep}px`,
})
</script>

<style scoped>
.inspira-tree {
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.tree-list {
  position: relative;
  min-width: max-content;
  width: max-content;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 2px 8px 6px;
}

.tree-row {
  position: relative;
  display: grid;
  grid-template-columns: 18px 18px max-content max-content;
  align-items: center;
  gap: 8px;
  width: max-content;
  min-width: 100%;
  padding: 7px 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: #334155;
  text-align: left;
  cursor: pointer;
  transition:
    background-color 0.18s ease,
    color 0.18s ease,
    transform 0.18s ease,
    box-shadow 0.18s ease;
}

.tree-row:hover {
  background: rgba(59, 130, 246, 0.06);
  color: #0f172a;
}

.tree-row.selected {
  background: linear-gradient(90deg, rgba(37, 99, 235, 0.14), rgba(37, 99, 235, 0.06));
  box-shadow: inset 0 0 0 1px rgba(37, 99, 235, 0.16);
  color: #0f172a;
}

.tree-row:disabled {
  cursor: default;
  opacity: 0.72;
}

.tree-row-indicator {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
}

.tree-caret {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
}

.tree-leaf-bullet {
  width: 5px;
  height: 5px;
  border-radius: 999px;
  background: #cbd5e1;
}

.tree-row-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  flex: none;
  color: #f59e0b;
  font-size: 13px;
}

.tree-row-icon.is-folder {
  color: #f59e0b;
}

.tree-file-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  height: 14px;
  padding: 0 4px;
  border-radius: 4px;
  color: #fff;
  font-size: 9px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: 0;
}

.tree-row-icon.is-html .tree-file-badge,
.tree-row-icon.is-xml .tree-file-badge {
  background: #f97316;
}

.tree-row-icon.is-css .tree-file-badge,
.tree-row-icon.is-scss .tree-file-badge {
  background: #ec4899;
}

.tree-row-icon.is-js .tree-file-badge,
.tree-row-icon.is-jsx .tree-file-badge {
  background: #facc15;
  color: #1f2937;
}

.tree-row-icon.is-ts .tree-file-badge,
.tree-row-icon.is-tsx .tree-file-badge {
  background: #2563eb;
}

.tree-row-icon.is-vue .tree-file-badge {
  background: #10b981;
}

.tree-row-icon.is-json .tree-file-badge {
  background: #7c3aed;
}

.tree-row-icon.is-md .tree-file-badge {
  background: #0f766e;
}

.tree-row-icon.is-image .tree-file-badge {
  background: #0ea5e9;
}

.tree-row-icon.is-sql .tree-file-badge {
  background: #334155;
}

.tree-row-icon.is-go .tree-file-badge {
  background: #0891b2;
}

.tree-row-icon.is-yaml .tree-file-badge,
.tree-row-icon.is-text .tree-file-badge,
.tree-row-icon.is-env .tree-file-badge,
.tree-row-icon.is-lock .tree-file-badge,
.tree-row-icon.is-file .tree-file-badge {
  background: #64748b;
}

.tree-row-name {
  min-width: 0;
  white-space: nowrap;
  font-size: 13px;
}

.tree-row-count {
  align-self: center;
  padding: 0 7px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.14);
  color: #64748b;
  font-size: 11px;
  line-height: 18px;
}

.tree-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 18px;
  color: #64748b;
  font-size: 13px;
  text-align: center;
}
</style>
