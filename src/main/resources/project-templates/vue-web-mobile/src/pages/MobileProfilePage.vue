<template>
  <div class="mobile-page">
    <van-nav-bar title="个人中心" />
    <section class="profile-hero">
      <div>
        <span class="eyebrow">PLUS MEMBER</span>
        <h1>晚上好，林若溪</h1>
        <p>已连续下单 14 周，会员权益已自动续期。</p>
      </div>
      <van-button round type="primary">查看会员</van-button>
    </section>

    <div class="profile-grid">
      <article v-for="item in profileCards" :key="item.title" class="profile-card">
        <span>{{ item.title }}</span>
        <strong>{{ item.value }}</strong>
      </article>
    </div>

    <van-cell-group inset>
      <van-cell title="收货地址" value="管理地址" is-link />
      <van-cell title="发票与报销" value="常用信息" is-link />
      <van-cell title="在线客服" value="联系客服" is-link />
      <van-cell title="设置" value="通知、隐私、账号" is-link />
    </van-cell-group>
  </div>
</template>

<script setup>
import { profileCards } from '@/data/mobileData'
</script>
