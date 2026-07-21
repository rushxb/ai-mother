// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 获取支持的模型目录 GET /ai-model/catalog */
export async function listSupportedModels(options?: { [key: string]: any }) {
  return request<API.BaseResponseListSupportedAiModelVO>('/ai-model/catalog', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 添加模型 POST /ai-model/add */
export async function addModel(body: API.AiModelAddRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponseLong>('/ai-model/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 删除模型 POST /ai-model/delete */
export async function deleteModel(body: API.DeleteRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponseBoolean>('/ai-model/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 根据 id 获取模型 GET /ai-model/get */
export async function getModelById(
  params: API.getModelByIdParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseAiModel>('/ai-model/get', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 获取所有启用的模型 GET /ai-model/list/enabled */
export async function listEnabledModels(options?: { [key: string]: any }) {
  return request<API.BaseResponseListAiModel>('/ai-model/list/enabled', {
    method: 'GET',
    ...(options || {}),
  })
}

/** 根据类型获取启用的模型 GET /ai-model/list/enabled/type */
export async function listEnabledModelsByType(
  params: API.listEnabledModelsByTypeParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseListAiModel>('/ai-model/list/enabled/type', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 分页获取模型列表 POST /ai-model/list/page */
export async function listModelsByPage(
  body: API.AiModelQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePageAiModel>('/ai-model/list/page', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 测试模型连接 POST /ai-model/test */
export async function testModelConnection(
  body: API.DeleteRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>('/ai-model/test', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 使用当前配置测试模型连接 POST /ai-model/test/config */
export async function testModelConnectionByConfig(
  body: API.AiModelAddRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseAiModelConnectionTestResultVO>('/ai-model/test/config', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 切换模型启用状态 POST /ai-model/toggle */
export async function toggleModelEnabled(
  body: API.AiModelToggleRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseAiModel>('/ai-model/toggle', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 更新模型 POST /ai-model/update */
export async function updateModel(
  body: API.AiModelUpdateRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>('/ai-model/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
