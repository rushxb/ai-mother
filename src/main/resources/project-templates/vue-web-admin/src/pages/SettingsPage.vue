<template>
  <section class="settings-grid">
    <form class="panel settings-form">
      <h2>基础设置</h2>
      <label>
        系统名称
        <input value="Console Pro" />
      </label>
      <label>
        通知邮箱
        <input value="admin@example.com" />
      </label>
      <label>
        数据同步
        <select>
          <option>每小时</option>
          <option>每天</option>
        </select>
      </label>
      <button class="primary" type="button">保存设置</button>
    </form>
    <article class="panel">
      <h2>权限说明</h2>
      <p>这里预留角色、菜单、审计日志、系统偏好等设置入口，AI 可根据业务继续拆分。</p>
    </article>
  </section>
</template>
