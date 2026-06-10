<template>
  <div class="admin-shell">
    <Sidebar />
    <section class="workspace">
      <Navbar />
      <RouterView />
    </section>
  </div>
</template>

<script setup>
import { RouterView } from 'vue-router'
import Navbar from './Navbar.vue'
import Sidebar from './Sidebar.vue'
</script>
