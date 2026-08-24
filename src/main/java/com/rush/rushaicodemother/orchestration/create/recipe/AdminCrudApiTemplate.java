package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.camel;
import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.escape;

/** 为全栈管理工作台渲染与 Go CRUD 路由严格对齐的类型化 API 客户端。 */
@Component
final class AdminCrudApiTemplate {

    /** 返回插入 {@code src/services/api.ts} 的 CRUD 客户端区块。 */
    String crudApi(AdminRecipe recipe) {
        CrudApiContract contract = recipe.crudApiContract()
                .orElseThrow(() -> new IllegalArgumentException("本地管理 recipe 不能渲染全栈 API 客户端"));
        String writableFields = recipe.fields().stream()
                .map(field -> "'" + escape(camel(field.name())) + "'")
                .distinct()
                .collect(Collectors.joining(", "));
        String listImplementation = contract.paginated()
                ? paginatedListImplementation(contract.listPath())
                : unpagedListImplementation(contract.listPath());
        return """

                // 全栈 CREATE recipe 生成区块：路径与 Go Handler 共用 CrudApiContract。
                export interface GeneratedRecordQuery {
                  current: number
                  pageSize: number
                  keyword?: string
                  status?: string
                }

                export interface GeneratedRecordPage {
                  records: GeneratedAdminRecord[]
                  total: number
                  current: number
                  pageSize: number
                }

                interface GeneratedApiRecord {
                  id: number
                  status?: string
                  [key: string]: unknown
                }

                export interface GeneratedAdminRecord {
                  id?: number
                  no: string
                  status: string
                  [key: string]: string | number | boolean | undefined
                }

                const GENERATED_RECORD_FIELDS = [%s] as const

                function normalizeGeneratedRecord(record: GeneratedApiRecord): GeneratedAdminRecord {
                  return {
                    ...record,
                    id: record.id,
                    no: String(record.id),
                    status: String(record.status ?? '待处理')
                  } as unknown as GeneratedAdminRecord
                }

                function toGeneratedPayload(record: GeneratedAdminRecord): Record<string, string | number | boolean> {
                  const payload: Record<string, string | number | boolean> = {}
                  for (const field of GENERATED_RECORD_FIELDS) {
                    const value = record[field]
                    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
                      payload[field] = value
                    }
                  }
                  return payload
                }

                function requireGeneratedRecordId(record: GeneratedAdminRecord): number {
                  const id = Number(record.id ?? record.no)
                  if (!Number.isSafeInteger(id) || id <= 0) {
                    throw new Error('记录 ID 无效，无法提交后端 CRUD 操作')
                  }
                  return id
                }

                %s

                export async function createGeneratedRecord(record: GeneratedAdminRecord): Promise<number> {
                  const response = await post<number>('%s', toGeneratedPayload(record))
                  return response.data
                }

                export async function updateGeneratedRecord(record: GeneratedAdminRecord): Promise<void> {
                  await put<boolean>('%s', {
                    id: requireGeneratedRecordId(record),
                    ...toGeneratedPayload(record)
                  })
                }

                export async function deleteGeneratedRecord(record: GeneratedAdminRecord): Promise<void> {
                  const id = requireGeneratedRecordId(record)
                  await del<boolean>(`%s/${id}`)
                }
                """.formatted(
                writableFields,
                listImplementation,
                contract.collectionPath(),
                contract.collectionPath(),
                contract.collectionPath()
        );
    }

    private String paginatedListImplementation(String listPath) {
        return """
                export async function loadGeneratedRecords(query: GeneratedRecordQuery): Promise<GeneratedRecordPage> {
                  const response = await post<{
                    records: GeneratedApiRecord[]
                    total: number
                    current: number
                    pageSize: number
                  }>('%s', query)
                  const page = response.data
                  return {
                    records: (page.records ?? []).map(normalizeGeneratedRecord),
                    total: page.total ?? 0,
                    current: page.current ?? query.current,
                    pageSize: page.pageSize ?? query.pageSize
                  }
                }
                """.formatted(listPath);
    }

    private String unpagedListImplementation(String listPath) {
        return """
                export async function loadGeneratedRecords(query: GeneratedRecordQuery): Promise<GeneratedRecordPage> {
                  const response = await post<GeneratedApiRecord[]>('%s', query)
                  const keyword = query.keyword?.trim().toLowerCase() ?? ''
                  const status = query.status?.trim() ?? ''
                  const records = (response.data ?? []).map(normalizeGeneratedRecord).filter(record => {
                    const matchesKeyword = !keyword || Object.values(record).some(value =>
                      String(value).toLowerCase().includes(keyword)
                    )
                    return matchesKeyword && (!status || record.status === status)
                  })
                  const start = (query.current - 1) * query.pageSize
                  return {
                    records: records.slice(start, start + query.pageSize),
                    total: records.length,
                    current: query.current,
                    pageSize: query.pageSize
                  }
                }
                """.formatted(listPath);
    }
}
