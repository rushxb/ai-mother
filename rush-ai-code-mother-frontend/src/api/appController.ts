// @ts-ignore
/* eslint-disable */
import request from '@/request'

/** 此处后端没有提供注释 POST /app/add */
export async function addApp(body: API.AppAddRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponseLong>('/app/add', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/admin/delete */
export async function deleteAppByAdmin(body: API.DeleteRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponseBoolean>('/app/admin/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /app/admin/get/vo */
export async function getAppVoByIdByAdmin(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getAppVOByIdByAdminParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseOwnerAppVO>('/app/admin/get/vo', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/admin/list/page/vo */
export async function listAppVoByPageByAdmin(
  body: API.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePageOwnerAppVO>('/app/admin/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/admin/update */
export async function updateAppByAdmin(
  body: API.AppAdminUpdateRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>('/app/admin/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 启动应用对话生成 POST /app/chat/gen/code */
export async function chatToGenCode(
  body: API.chatToGenCodeParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>('/app/chat/gen/code', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 停止应用对话生成 POST /app/chat/gen/code/stop */
export async function stopChatToGenCode(
  body: API.stopChatToGenCodeParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>('/app/chat/gen/code/stop', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 启用应用 Database 服务 POST /app/database/enable */
export async function enableAppDatabase(
  body: API.AppDatabaseEnableRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseAppDatabaseResourceVO>('/app/database/enable', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 优化提示词 POST /app/optimize/prompt */
export async function optimizePrompt(
  body: API.PromptOptimizeRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseString>('/app/optimize/prompt', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/copy */
export async function copyApp(body: API.AppCopyRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponseLong>('/app/copy', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/delete */
export async function deleteApp(body: API.DeleteRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponseBoolean>('/app/delete', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/deploy */
export async function deployApp(body: API.AppDeployRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponseString>('/app/deploy', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 启动应用的 Vue 开发服务器 POST /app/dev-server/start */
export async function startDevServer(
  params: { appId: string | number },
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseDevServerStatusVO>('/app/dev-server/start', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 停止应用的 Vue 开发服务器 POST /app/dev-server/stop */
export async function stopDevServer(
  params: { appId: string | number },
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>('/app/dev-server/stop', {
    method: 'POST',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 获取应用的 Vue 开发服务器状态 GET /app/dev-server/status */
export async function getDevServerStatus(
  params: { appId: string | number },
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseDevServerStatusVO>('/app/dev-server/status', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 获取应用代码文件树 GET /app/code/files */
export async function listAppCodeFiles(
  params: API.listAppCodeFilesParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseListAppCodeFileTreeVO>('/app/code/files', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 获取应用代码文件内容 GET /app/code/file */
export async function getAppCodeFileContent(
  params: API.getAppCodeFileContentParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseAppCodeFileContentVO>('/app/code/file', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 保存应用代码文件 POST /app/code/file/save */
export async function saveAppCodeFile(
  body: API.AppCodeFileSaveRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseBoolean>('/app/code/file/save', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 同步当前生成代码到部署目录 POST /app/deploy/sync */
export async function syncAppDeployment(
  body: API.AppDeployRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseString>('/app/deploy/sync', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /app/download/${param0} */
export async function downloadAppCode(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.downloadAppCodeParams,
  options?: { [key: string]: any }
) {
  const { appId: param0, ...queryParams } = params
  return request<any>(`/app/download/${param0}`, {
    method: 'GET',
    params: { ...queryParams },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 GET /app/get/vo */
export async function getAppVoById(
  // 叠加生成的Param类型 (非body参数swagger默认没有生成对象)
  params: API.getAppVOByIdParams,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponseOwnerAppVO>('/app/get/vo', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/good/list/page/vo */
export async function listGoodAppVoByPage(
  body: API.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePagePublicAppVO>('/app/good/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/my/list/page/vo */
export async function listMyAppVoByPage(
  body: API.AppQueryRequest,
  options?: { [key: string]: any }
) {
  return request<API.BaseResponsePageOwnerAppVO>('/app/my/list/page/vo', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}

/** 此处后端没有提供注释 POST /app/update */
export async function updateApp(body: API.AppUpdateRequest, options?: { [key: string]: any }) {
  return request<API.BaseResponseBoolean>('/app/update', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  })
}
