package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

/**
 * 管理工作台的持久化行为模板。
 *
 * <p>页面模板只拥有视图骨架；本地演示与全栈远端持久化的 import、监听器和命令处理
 * 集中在此模块，避免场景分支散落在大段 Vue 模板中。</p>
 */
@Component
final class AdminDashboardPersistenceTemplate {

    PersistenceView render(AdminRecipe recipe) {
        if (recipe.apiBacked()) {
            return new PersistenceView(
                    true,
                    "数据通过生成的 Go CRUD API 持久化，搜索和分页由后端执行。",
                    "保存后写入生成的 Go API，并刷新后端分页数据。",
                    "computed, onBeforeUnmount, onMounted, reactive, ref, watch",
                    "",
                    remoteApiImport(),
                    remoteWatchers(),
                    remotePersistenceSupport(),
                    remoteMutationHandlers()
            );
        }
        return new PersistenceView(
                false,
                "支持本地新增、编辑、删除、筛选与导出。",
                "保存后立即更新当前工作台的本地数据。",
                "computed, reactive, ref, watch",
                ",\n  type AdminRecord",
                "type DashboardRecord = AdminRecord",
                localWatchers(),
                "",
                localMutationHandlers()
        );
    }

    private String remoteApiImport() {
        return """
                import {
                  createGeneratedRecord,
                  deleteGeneratedRecord,
                  loadGeneratedRecords,
                  updateGeneratedRecord,
                  type GeneratedAdminRecord
                } from '@/services/api'
                type DashboardRecord = GeneratedAdminRecord
                """;
    }

    private String localWatchers() {
        return """
                watch([searchQuery, statusFilter], () => {
                  currentPage.value = 1
                })
                """;
    }

    private String remoteWatchers() {
        return """
                let reloadTimer: number | undefined

                watch([searchQuery, statusFilter], () => {
                  window.clearTimeout(reloadTimer)
                  if (currentPage.value !== 1) {
                    currentPage.value = 1
                    return
                  }
                  reloadTimer = window.setTimeout(() => void refreshRemoteRecords(), 250)
                })
                watch(currentPage, () => void refreshRemoteRecords())
                onMounted(() => void refreshRemoteRecords())
                onBeforeUnmount(() => window.clearTimeout(reloadTimer))
                """;
    }

    private String remotePersistenceSupport() {
        return """
                let latestLoadVersion = 0

                async function refreshRemoteRecords(): Promise<void> {
                  const loadVersion = ++latestLoadVersion
                  try {
                    const page = await loadGeneratedRecords({
                      current: currentPage.value,
                      pageSize,
                      keyword: searchQuery.value.trim() || undefined,
                      status: statusFilter.value || undefined
                    })
                    if (loadVersion !== latestLoadVersion) return
                    const lastPage = Math.max(1, Math.ceil(page.total / pageSize))
                    if (currentPage.value > lastPage) {
                      currentPage.value = lastPage
                      return
                    }
                    records.value = page.records
                    remoteTotal.value = page.total
                    selectedRecordIds.value = []
                    persistenceError.value = ''
                  } catch (error) {
                    if (loadVersion !== latestLoadVersion) return
                    persistenceError.value = `后端数据加载失败：${errorMessage(error)}。当前保留最近一次可用数据。`
                    remoteTotal.value = records.value.length
                  }
                }

                function errorMessage(error: unknown): string {
                  return error instanceof Error && error.message ? error.message : '未知错误'
                }
                """;
    }

    private String localMutationHandlers() {
        return """
                function submitRecord(): void {
                  const record = { ...draft } as DashboardRecord
                  const existingIndex = records.value.findIndex(item => item.no === editingRecordNo.value)
                  if (existingIndex >= 0) records.value[existingIndex] = record
                  else records.value.unshift(record)
                  closeModal()
                }

                function deleteRecord(record: DashboardRecord): void {
                  records.value = records.value.filter(item => item.no !== record.no)
                  selectedRecordIds.value = selectedRecordIds.value.filter(item => item !== record.no)
                }

                function deleteSelected(): void {
                  const selected = new Set(selectedRecordIds.value)
                  records.value = records.value.filter(item => !selected.has(item.no))
                  selectedRecordIds.value = []
                }
                """;
    }

    private String remoteMutationHandlers() {
        return """
                async function submitRecord(): Promise<void> {
                  saving.value = true
                  persistenceError.value = ''
                  try {
                    const record = { ...draft } as DashboardRecord
                    if (editingRecordNo.value) await updateGeneratedRecord(record)
                    else await createGeneratedRecord(record)
                    closeModal()
                    await refreshRemoteRecords()
                  } catch (error) {
                    persistenceError.value = `保存失败：${errorMessage(error)}`
                  } finally {
                    saving.value = false
                  }
                }

                async function deleteRecord(record: DashboardRecord): Promise<void> {
                  saving.value = true
                  persistenceError.value = ''
                  try {
                    await deleteGeneratedRecord(record)
                    await refreshRemoteRecords()
                  } catch (error) {
                    persistenceError.value = `删除失败：${errorMessage(error)}`
                  } finally {
                    saving.value = false
                  }
                }

                async function deleteSelected(): Promise<void> {
                  const selected = new Set(selectedRecordIds.value)
                  const selectedRecords = records.value.filter(record => selected.has(record.no))
                  if (selectedRecords.length === 0) return
                  saving.value = true
                  persistenceError.value = ''
                  try {
                    await Promise.all(selectedRecords.map(record => deleteGeneratedRecord(record)))
                    await refreshRemoteRecords()
                  } catch (error) {
                    persistenceError.value = `批量删除失败：${errorMessage(error)}`
                  } finally {
                    saving.value = false
                  }
                }
                """;
    }

    record PersistenceView(
            boolean apiBacked,
            String managementDescription,
            String modalDescription,
            String vueImports,
            String dataRecordImportSuffix,
            String apiImport,
            String watchers,
            String supportFunctions,
            String mutationHandlers
    ) {
    }
}
