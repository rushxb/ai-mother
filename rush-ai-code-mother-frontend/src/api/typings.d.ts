declare namespace API {
  type AppAddRequest = {
    initPrompt?: string
  }

  type AppAdminUpdateRequest = {
    id?: string | number
    appName?: string
    cover?: string
    priority?: number
  }

  type AppCopyRequest = {
    sourceAppId?: string | number
  }

  type AppDeployRequest = {
    appId?: string | number
  }

  type AppDatabaseEnableRequest = {
    appId?: string | number
  }

  type AppDatabaseResourceVO = {
    id?: string | number
    appId?: string | number
    resourceId?: string
    resourceName?: string
    databaseUrl?: string
    dbEngine?: string
    backendRuntime?: string
    sqlExecutionPolicy?: string
    status?: string
    lastUsedTime?: string
    createTime?: string
    updateTime?: string
    enabled?: boolean
  }

  type AppCodeFileSaveRequest = {
    appId?: string | number
    filePath?: string
    content?: string
  }

  type AppCodeFileTreeVO = {
    name?: string
    path?: string
    directory?: boolean
    size?: number
    children?: AppCodeFileTreeVO[]
  }

  type AppCodeFileContentVO = {
    path?: string
    name?: string
    content?: string
    size?: number
    editable?: boolean
  }

  type AppQueryRequest = {
    pageNum?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    id?: string | number
    appName?: string
    cover?: string
    initPrompt?: string
    codeGenType?: string
    deployKey?: string
    priority?: number
    userId?: string | number
  }

  type AppUpdateRequest = {
    id?: string | number
    appName?: string
  }

  type AppVO = {
    id?: string | number
    appName?: string
    cover?: string
    initPrompt?: string
    codeGenType?: string
    deployKey?: string
    deployedTime?: string
    isGenerating?: number
    generatingMessage?: string
    generatingStage?: string
    devServerPort?: number
    databaseResource?: AppDatabaseResourceVO
    priority?: number
    userId?: string | number
    createTime?: string
    updateTime?: string
    user?: UserVO
  }

  type DevServerStatusVO = {
    appId?: string | number
    running?: boolean
    port?: number
    previewUrl?: string
    status?: string
  }

  type BaseResponseAppVO = {
    code?: number
    data?: AppVO
    message?: string
  }

  type BaseResponseAppCodeFileContentVO = {
    code?: number
    data?: AppCodeFileContentVO
    message?: string
  }

  type BaseResponseAppDatabaseResourceVO = {
    code?: number
    data?: AppDatabaseResourceVO
    message?: string
  }

  type BaseResponseDevServerStatusVO = {
    code?: number
    data?: DevServerStatusVO
    message?: string
  }

  type BaseResponseListAppCodeFileTreeVO = {
    code?: number
    data?: AppCodeFileTreeVO[]
    message?: string
  }

  type BaseResponseBoolean = {
    code?: number
    data?: boolean
    message?: string
  }

  type BaseResponseLoginUserVO = {
    code?: number
    data?: LoginUserVO
    message?: string
  }

  type BaseResponseLong = {
    code?: number
    data?: string | number
    message?: string
  }

  type BaseResponsePageAppVO = {
    code?: number
    data?: PageAppVO
    message?: string
  }

  type BaseResponsePageChatHistory = {
    code?: number
    data?: PageChatHistory
    message?: string
  }

  type BaseResponsePageUserVO = {
    code?: number
    data?: PageUserVO
    message?: string
  }

  type BaseResponseString = {
    code?: number
    data?: string
    message?: string
  }

  type GenerationPerformanceSpanVO = {
    stage?: string
    status?: string
    durationMs?: number
    detail?: string
  }

  type GenerationPerformanceTaskVO = {
    taskId?: string
    appId?: string | number
    userId?: string | number
    route?: string
    targetType?: string
    status?: string
    totalDurationMs?: number
    startTime?: string
    endTime?: string
    spans?: GenerationPerformanceSpanVO[]
  }

  type GenerationPerformanceStageStatsVO = {
    stage?: string
    count?: number
    avgDurationMs?: number
    p50DurationMs?: number
    p90DurationMs?: number
    maxDurationMs?: number
  }

  type GenerationPerformanceSummaryVO = {
    taskCount?: number
    successCount?: number
    failedCount?: number
    runningCount?: number
    avgTotalDurationMs?: number
    p50TotalDurationMs?: number
    p90TotalDurationMs?: number
    stageStats?: GenerationPerformanceStageStatsVO[]
    recentTasks?: GenerationPerformanceTaskVO[]
  }

  type BaseResponseGenerationPerformanceSummaryVO = {
    code?: number
    data?: GenerationPerformanceSummaryVO
    message?: string
  }

  type getGenerationPerformanceSummaryParams = {
    limit?: number
  }

  type PromptOptimizeRequest = {
    prompt?: string
  }

  type BaseResponseUser = {
    code?: number
    data?: User
    message?: string
  }

  type BaseResponseUserVO = {
    code?: number
    data?: UserVO
    message?: string
  }

  type ChatHistory = {
    id?: string | number
    message?: string
    messageType?: string
    appId?: string | number
    userId?: string | number
    createTime?: string
    updateTime?: string
    isDelete?: number
  }

  type ChatHistoryQueryRequest = {
    pageNum?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    id?: string | number
    message?: string
    messageType?: string
    appId?: string | number
    userId?: string | number
    lastCreateTime?: string
  }

  type chatToGenCodeParams = {
    appId: string | number
    message: string
  }

  type stopChatToGenCodeParams = {
    appId: string | number
  }

  type DeleteRequest = {
    id?: string | number
  }

  type downloadAppCodeParams = {
    appId: string | number
  }

  type getAppVOByIdByAdminParams = {
    id: string | number
  }

  type getAppVOByIdParams = {
    id: string | number
  }

  type getAppCodeFileContentParams = {
    appId: string | number
    filePath: string
  }

  type listAppCodeFilesParams = {
    appId: string | number
  }

  type getUserByIdParams = {
    id: number
  }

  type getUserVOByIdParams = {
    id: number
  }

  type listAppChatHistoryParams = {
    appId: string | number
    pageSize?: number
    lastCreateTime?: string
  }

  type LoginUserVO = {
    id?: number
    userAccount?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    creditBalance?: number
    createTime?: string
    updateTime?: string
  }

  type PageAppVO = {
    records?: AppVO[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type PageChatHistory = {
    records?: ChatHistory[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type PageUserVO = {
    records?: UserVO[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type ServerSentEventString = true

  type serveStaticResourceParams = {
    deployKey: string
  }

  type User = {
    id?: number
    userAccount?: string
    userPassword?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    creditBalance?: number
    editTime?: string
    createTime?: string
    updateTime?: string
    isDelete?: number
  }

  type UserAddRequest = {
    userName?: string
    userAccount?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    creditBalance?: number
  }

  type UserCreditAdjustRequest = {
    userId?: number
    changeAmount?: number
    remark?: string
  }

  type UserLoginRequest = {
    userAccount?: string
    userPassword?: string
  }

  type UserQueryRequest = {
    pageNum?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    id?: number
    userName?: string
    userAccount?: string
    userProfile?: string
    userRole?: string
  }

  type UserRegisterRequest = {
    userAccount?: string
    userPassword?: string
    checkPassword?: string
  }

  type UserUpdateRequest = {
    id?: number
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    creditBalance?: number
  }

  type UserVO = {
    id?: number
    userAccount?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    creditBalance?: number
    createTime?: string
  }

  // ========== AI 模型配置相关类型 ==========

  type AiModel = {
    id?: number
    modelName?: string
    provider?: string
    modelId?: string
    description?: string
    baseUrl?: string
    apiKey?: string
    maxTokens?: number
    temperature?: number
    isEnabled?: number
    modelType?: string
    supportsThinking?: number
    sortOrder?: number
    configJson?: string
    userId?: number
    editTime?: string
    createTime?: string
    updateTime?: string
    isDelete?: number
  }

  type AiModelAddRequest = {
    modelName?: string
    provider?: string
    modelId?: string
    description?: string
    baseUrl?: string
    apiKey?: string
    maxTokens?: number
    temperature?: number
    isEnabled?: number
    modelType?: string
    supportsThinking?: number
    sortOrder?: number
    configJson?: string
    protocol?: string
  }

  type AiModelUpdateRequest = {
    id?: number
    modelName?: string
    provider?: string
    modelId?: string
    description?: string
    baseUrl?: string
    apiKey?: string
    maxTokens?: number
    temperature?: number
    isEnabled?: number
    modelType?: string
    supportsThinking?: number
    sortOrder?: number
    configJson?: string
    protocol?: string
  }

  type AiModelQueryRequest = {
    pageNum?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    provider?: string
    modelType?: string
    isEnabled?: number
    keyword?: string
  }

  type SupportedAiModelVO = {
    provider?: string
    providerLabel?: string
    modelId?: string
    modelName?: string
    defaultBaseUrl?: string
    defaultProtocol?: string
    supportedProtocols?: string[]
    supportedModelTypes?: string[]
    defaultModelType?: string
    supportsThinking?: number
    defaultMaxTokens?: number
    defaultTemperature?: number
    minTemperature?: number
    maxTemperature?: number
  }

  type AiModelConnectionTestResultVO = {
    success?: boolean
    message?: string
  }

  type BaseResponseAiModel = {
    code?: number
    data?: AiModel
    message?: string
  }

  type BaseResponseListAiModel = {
    code?: number
    data?: AiModel[]
    message?: string
  }

  type BaseResponseListSupportedAiModelVO = {
    code?: number
    data?: SupportedAiModelVO[]
    message?: string
  }

  type BaseResponseAiModelConnectionTestResultVO = {
    code?: number
    data?: AiModelConnectionTestResultVO
    message?: string
  }

  type BaseResponsePageAiModel = {
    code?: number
    data?: PageAiModel
    message?: string
  }

  type PageAiModel = {
    records?: AiModel[]
    pageNumber?: number
    pageSize?: number
    totalPage?: number
    totalRow?: number
    optimizeCountQuery?: boolean
  }

  type getModelByIdParams = {
    id: number
  }

  type listEnabledModelsByTypeParams = {
    modelType: string
  }
}
