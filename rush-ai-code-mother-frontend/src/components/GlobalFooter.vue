<template>
  <a-layout-footer class="footer">
    <div class="footer-shell">
      <div class="footer-brand">
        <span class="footer-mark" aria-hidden="true">R</span>
        <div>
          <strong>Rush AI Code Mother</strong>
          <p>从需求描述到可运行应用的智能创作工作台</p>
        </div>
      </div>

      <div class="footer-meta">
        <span class="workspace-status"><i aria-hidden="true"></i>AI APPLICATION WORKSPACE</span>
        <span>© {{ currentYear }} Rush Studio</span>
      </div>
    </div>
  </a-layout-footer>
</template>

<script setup lang="ts">
const currentYear = new Date().getFullYear()
</script>

<style scoped>
.footer {
  position: relative;
  z-index: 2;
  margin: 0;
  padding: 0 24px 26px;
  overflow: hidden;
  border: 0;
  background:
    linear-gradient(180deg, rgba(246, 249, 253, 0), rgba(240, 246, 251, 0.9)), transparent;
}

.footer-shell {
  position: relative;
  display: flex;
  width: min(var(--page-max-width), 100%);
  min-height: 104px;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  margin: 0 auto;
  padding: 23px 26px;
  overflow: hidden;
  border: 1px solid rgba(119, 150, 187, 0.14);
  border-radius: 22px;
  background:
    radial-gradient(circle at 0 0, rgba(47, 139, 255, 0.09), transparent 32%),
    linear-gradient(145deg, rgba(255, 255, 255, 0.88), rgba(246, 251, 252, 0.8));
  box-shadow:
    0 18px 52px rgba(70, 101, 140, 0.07),
    inset 0 1px 0 rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(18px);
}

.footer-shell::after {
  position: absolute;
  right: -28px;
  bottom: -52px;
  width: 180px;
  height: 110px;
  border: 1px solid rgba(60, 201, 187, 0.18);
  border-radius: 50%;
  content: '';
  transform: rotate(-12deg);
}

.footer-brand,
.footer-meta {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
}

.footer-brand {
  gap: 13px;
}

.footer-mark {
  display: grid;
  width: 43px;
  height: 43px;
  flex: 0 0 auto;
  place-items: center;
  border: 1px solid rgba(47, 139, 255, 0.18);
  border-radius: 14px;
  background: linear-gradient(145deg, rgba(47, 139, 255, 0.13), rgba(60, 201, 187, 0.13));
  color: var(--color-primary-strong);
  font-size: 17px;
  font-weight: 850;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9);
}

.footer-brand strong {
  color: var(--color-ink-strong);
  font-size: 14px;
  letter-spacing: -0.01em;
}

.footer-brand p {
  margin: 4px 0 0;
  color: var(--color-ink-soft);
  font-size: 11px;
}

.footer-meta {
  align-items: flex-end;
  flex-direction: column;
  gap: 7px;
  color: #8a9aaf;
  font-size: 10px;
  letter-spacing: 0.04em;
}

.workspace-status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #60738a;
  font-weight: 760;
  letter-spacing: 0.1em;
}

.workspace-status i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--color-secondary);
  box-shadow: 0 0 0 5px rgba(60, 201, 187, 0.1);
}

@media (max-width: 720px) {
  .footer {
    padding: 0 12px 18px;
  }

  .footer-shell {
    align-items: flex-start;
    flex-direction: column;
    padding: 20px;
  }

  .footer-meta {
    align-items: flex-start;
  }
}
</style>
