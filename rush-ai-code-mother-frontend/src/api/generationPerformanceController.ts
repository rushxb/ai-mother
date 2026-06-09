import request from '@/request'

export async function getGenerationPerformanceSummary(
  params?: API.getGenerationPerformanceSummaryParams,
  options?: { [key: string]: any },
) {
  return request<API.BaseResponseGenerationPerformanceSummaryVO>(
    '/generation-performance/admin/summary',
    {
      method: 'GET',
      params,
      ...(options || {}),
    },
  )
}
