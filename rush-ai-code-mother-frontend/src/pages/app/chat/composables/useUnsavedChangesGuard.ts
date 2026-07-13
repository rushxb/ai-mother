import { onBeforeUnmount, onMounted, type Ref } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'

export interface UnsavedChangesGuardOptions {
  isDirty: Readonly<Ref<boolean>>
  message?: string
}

export const useUnsavedChangesGuard = ({
  isDirty,
  message = '当前文件有未保存的修改，确定要离开吗？',
}: UnsavedChangesGuardOptions) => {
  const confirmLeave = () => !isDirty.value || window.confirm(message)

  const handleBeforeUnload = (event: BeforeUnloadEvent) => {
    if (!isDirty.value) {
      return
    }
    event.preventDefault()
    event.returnValue = ''
  }

  onBeforeRouteLeave(() => confirmLeave())
  // Reused route components need a separate guard when only the :id parameter changes.
  onBeforeRouteUpdate(() => confirmLeave())
  onMounted(() => window.addEventListener('beforeunload', handleBeforeUnload))
  onBeforeUnmount(() => window.removeEventListener('beforeunload', handleBeforeUnload))

  return { confirmLeave }
}
