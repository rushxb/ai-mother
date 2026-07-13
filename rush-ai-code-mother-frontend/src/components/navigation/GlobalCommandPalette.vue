<template>
  <Teleport to="body">
    <Transition name="command-palette">
      <div v-if="open" class="command-backdrop" role="presentation" @mousedown.self="closePalette">
        <section
          class="command-dialog"
          role="dialog"
          aria-modal="true"
          aria-labelledby="command-palette-title"
        >
          <header class="command-search-shell">
            <SearchOutlined class="search-icon" aria-hidden="true" />
            <div class="search-copy">
              <h2 id="command-palette-title">工作台命令中心</h2>
              <input
                ref="searchInputRef"
                v-model="query"
                class="command-search"
                type="search"
                autocomplete="off"
                spellcheck="false"
                aria-label="搜索页面和操作"
                :aria-activedescendant="activeItem ? `command-item-${activeItem.id}` : undefined"
                placeholder="搜索页面、管理功能或快捷操作…"
                @keydown="handleSearchKeydown"
              />
            </div>
            <button
              type="button"
              class="escape-key"
              aria-label="关闭命令面板"
              @click="closePalette"
            >
              ESC
            </button>
          </header>

          <div ref="resultsRef" class="command-results" role="listbox" aria-label="可用命令">
            <template v-if="groupedItems.length">
              <section v-for="group in groupedItems" :key="group.name" class="command-group">
                <h3>{{ group.name }}</h3>
                <button
                  v-for="item in group.items"
                  :id="`command-item-${item.id}`"
                  :key="item.id"
                  type="button"
                  class="command-item"
                  :class="{ active: item.id === activeItem?.id }"
                  :disabled="item.disabled"
                  role="option"
                  :aria-selected="item.id === activeItem?.id"
                  @mouseenter="setActiveItem(item.id)"
                  @click="selectItem(item)"
                >
                  <span class="command-item-icon" aria-hidden="true">
                    <component :is="item.icon" v-if="item.icon" />
                    <ThunderboltOutlined v-else />
                  </span>
                  <span class="command-item-copy">
                    <strong>{{ item.title }}</strong>
                    <small>{{ item.description }}</small>
                  </span>
                  <kbd v-if="item.shortcut">{{ item.shortcut }}</kbd>
                  <RightOutlined v-else class="command-item-arrow" aria-hidden="true" />
                </button>
              </section>
            </template>

            <div v-else class="command-empty" role="status">
              <span class="empty-orbit" aria-hidden="true"><SearchOutlined /></span>
              <strong>没有匹配的命令</strong>
              <p>换一个关键词，或按 ESC 返回当前页面。</p>
            </div>
          </div>

          <footer class="command-footer">
            <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span>
            <span><kbd>↵</kbd> 打开</span>
            <span class="command-footer-context">RUSH WORKSPACE / COMMAND CENTER</span>
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { RightOutlined, SearchOutlined, ThunderboltOutlined } from '@ant-design/icons-vue'
import type { CommandPaletteItem } from './commandPalette'

const props = defineProps<{
  open: boolean
  items: CommandPaletteItem[]
}>()

const emit = defineEmits<{
  'update:open': [open: boolean]
  select: [item: CommandPaletteItem]
}>()

const searchInputRef = ref<HTMLInputElement>()
const resultsRef = ref<HTMLElement>()
const query = ref('')
const activeIndex = ref(0)
let previousBodyOverflow = ''

const normalizedQuery = computed(() => query.value.trim().toLocaleLowerCase())

const filteredItems = computed(() => {
  if (!normalizedQuery.value) {
    return props.items
  }

  const searchTerms = normalizedQuery.value.split(/\s+/).filter(Boolean)
  return props.items.filter((item) => {
    const searchableText = [item.title, item.description, item.group, ...(item.keywords ?? [])]
      .join(' ')
      .toLocaleLowerCase()

    return searchTerms.every((term) => searchableText.includes(term))
  })
})

const enabledItems = computed(() => filteredItems.value.filter((item) => !item.disabled))
const activeItem = computed(() => enabledItems.value[activeIndex.value])

const groupedItems = computed(() => {
  const groups = new Map<string, CommandPaletteItem[]>()
  filteredItems.value.forEach((item) => {
    const currentGroup = groups.get(item.group) ?? []
    currentGroup.push(item)
    groups.set(item.group, currentGroup)
  })
  return Array.from(groups, ([name, items]) => ({ name, items }))
})

const closePalette = () => emit('update:open', false)

const ensureActiveItemVisible = async () => {
  await nextTick()
  if (!activeItem.value || !resultsRef.value) {
    return
  }
  const activeElement = resultsRef.value.querySelector<HTMLElement>(
    `#command-item-${CSS.escape(activeItem.value.id)}`,
  )
  activeElement?.scrollIntoView({ block: 'nearest' })
}

const moveActiveItem = (offset: number) => {
  const itemCount = enabledItems.value.length
  if (!itemCount) {
    activeIndex.value = 0
    return
  }
  activeIndex.value = (activeIndex.value + offset + itemCount) % itemCount
  void ensureActiveItemVisible()
}

const setActiveItem = (itemId: string) => {
  const nextIndex = enabledItems.value.findIndex((item) => item.id === itemId)
  if (nextIndex >= 0) {
    activeIndex.value = nextIndex
  }
}

const selectItem = (item: CommandPaletteItem) => {
  if (item.disabled) {
    return
  }
  emit('select', item)
  closePalette()
}

const handleSearchKeydown = (event: KeyboardEvent) => {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    moveActiveItem(1)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    moveActiveItem(-1)
    return
  }
  if (event.key === 'Enter' && activeItem.value) {
    event.preventDefault()
    selectItem(activeItem.value)
    return
  }
  if (event.key === 'Escape') {
    event.preventDefault()
    closePalette()
  }
}

watch(query, () => {
  activeIndex.value = 0
})

watch(
  () => props.items,
  () => {
    activeIndex.value = 0
  },
)

watch(
  () => props.open,
  async (isOpen) => {
    if (isOpen) {
      query.value = ''
      activeIndex.value = 0
      previousBodyOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
      await nextTick()
      searchInputRef.value?.focus()
      return
    }
    document.body.style.overflow = previousBodyOverflow
  },
)

onUnmounted(() => {
  document.body.style.overflow = previousBodyOverflow
})
</script>

<style scoped>
.command-backdrop {
  position: fixed;
  inset: 0;
  z-index: 2400;
  display: grid;
  place-items: start center;
  padding: min(16vh, 150px) 20px 24px;
  background:
    radial-gradient(circle at 50% 6%, rgba(47, 139, 255, 0.13), transparent 34%),
    rgba(7, 18, 33, 0.32);
  backdrop-filter: blur(14px) saturate(120%);
}

.command-dialog {
  width: min(680px, 100%);
  overflow: hidden;
  border: 1px solid rgba(171, 199, 231, 0.5);
  border-radius: 26px;
  background: rgba(250, 253, 255, 0.96);
  box-shadow:
    0 42px 120px rgba(8, 25, 48, 0.28),
    0 0 0 1px rgba(255, 255, 255, 0.72) inset;
}

.command-search-shell {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  min-height: 76px;
  padding: 0 20px 0 24px;
  border-bottom: 1px solid rgba(112, 140, 175, 0.16);
  background: rgba(255, 255, 255, 0.84);
}

.search-icon {
  color: var(--color-primary, #2f8bff);
  font-size: 20px;
}

.search-copy {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.search-copy h2 {
  margin: 0;
  color: #7a8ca3;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.command-search {
  width: 100%;
  min-width: 0;
  padding: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--color-ink-strong, #102033);
  font-size: 17px;
  line-height: 1.5;
}

.command-search::placeholder {
  color: rgba(82, 104, 132, 0.5);
}

.command-search::-webkit-search-cancel-button {
  display: none;
}

kbd,
.escape-key {
  border: 1px solid rgba(112, 140, 175, 0.2);
  border-bottom-color: rgba(112, 140, 175, 0.3);
  border-radius: 7px;
  background: rgba(238, 244, 251, 0.9);
  color: #667991;
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.9) inset;
  font-family: var(--font-ui, sans-serif);
  font-size: 10px;
  font-weight: 750;
  line-height: 1;
}

.escape-key {
  height: 28px;
  padding: 0 8px;
  cursor: pointer;
}

.command-results {
  max-height: min(56vh, 480px);
  padding: 10px;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.command-group + .command-group {
  margin-top: 4px;
  padding-top: 6px;
  border-top: 1px solid rgba(112, 140, 175, 0.1);
}

.command-group h3 {
  margin: 0;
  padding: 10px 12px 7px;
  color: #8090a5;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.command-item {
  width: 100%;
  min-height: 64px;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 1px solid transparent;
  border-radius: 16px;
  background: transparent;
  color: var(--color-ink-strong, #102033);
  text-align: left;
  cursor: pointer;
  transition:
    background 0.18s ease,
    border-color 0.18s ease,
    transform 0.18s ease;
}

.command-item.active {
  border-color: rgba(47, 139, 255, 0.16);
  background:
    linear-gradient(90deg, rgba(47, 139, 255, 0.1), rgba(60, 201, 187, 0.045)),
    rgba(255, 255, 255, 0.72);
  transform: translateX(2px);
}

.command-item:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.command-item-icon {
  width: 40px;
  height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(47, 139, 255, 0.12);
  border-radius: 13px;
  background: rgba(47, 139, 255, 0.07);
  color: var(--color-primary, #2f8bff);
  font-size: 17px;
}

.command-item-copy {
  min-width: 0;
  display: grid;
  gap: 3px;
}

.command-item-copy strong {
  overflow: hidden;
  font-size: 14px;
  font-weight: 720;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.command-item-copy small {
  overflow: hidden;
  color: #74869c;
  font-size: 12px;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.command-item kbd {
  padding: 5px 7px;
}

.command-item-arrow {
  margin-right: 2px;
  color: #a1afbf;
  font-size: 11px;
  transition: transform 0.18s ease;
}

.command-item.active .command-item-arrow {
  color: var(--color-primary, #2f8bff);
  transform: translateX(2px);
}

.command-empty {
  min-height: 230px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 8px;
  padding: 32px;
  text-align: center;
}

.empty-orbit {
  width: 52px;
  height: 52px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 6px;
  border: 1px solid rgba(47, 139, 255, 0.16);
  border-radius: 50%;
  background: rgba(47, 139, 255, 0.07);
  color: var(--color-primary, #2f8bff);
  font-size: 20px;
}

.command-empty strong {
  color: var(--color-ink-strong, #102033);
  font-size: 15px;
}

.command-empty p {
  margin: 0;
  color: #74869c;
  font-size: 13px;
}

.command-footer {
  min-height: 46px;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 18px;
  border-top: 1px solid rgba(112, 140, 175, 0.14);
  background: rgba(243, 248, 253, 0.82);
  color: #74869c;
  font-size: 11px;
}

.command-footer span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.command-footer kbd {
  min-width: 22px;
  padding: 4px 5px;
  text-align: center;
}

.command-footer-context {
  margin-left: auto;
  color: #8b9aae;
  font-size: 9px;
  font-weight: 750;
  letter-spacing: 0.13em;
}

.command-palette-enter-active,
.command-palette-leave-active {
  transition: opacity 0.2s ease;
}

.command-palette-enter-active .command-dialog,
.command-palette-leave-active .command-dialog {
  transition:
    transform 0.28s var(--ease-out, cubic-bezier(0.22, 1, 0.36, 1)),
    opacity 0.2s ease,
    filter 0.24s ease;
}

.command-palette-enter-from,
.command-palette-leave-to {
  opacity: 0;
}

.command-palette-enter-from .command-dialog,
.command-palette-leave-to .command-dialog {
  opacity: 0;
  filter: blur(8px);
  transform: translateY(-14px) scale(0.98);
}

@media (max-width: 640px) {
  .command-backdrop {
    padding: 12px;
    place-items: center;
  }

  .command-dialog {
    max-height: calc(100dvh - 24px);
    border-radius: 22px;
  }

  .command-search-shell {
    min-height: 66px;
    padding-inline: 18px 14px;
  }

  .command-search {
    font-size: 15px;
  }

  .command-results {
    max-height: calc(100dvh - 148px);
  }

  .command-item {
    min-height: 62px;
  }

  .command-footer-context {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .command-item,
  .command-item-arrow,
  .command-palette-enter-active,
  .command-palette-leave-active,
  .command-palette-enter-active .command-dialog,
  .command-palette-leave-active .command-dialog {
    transition-duration: 0.01ms !important;
  }
}
</style>
