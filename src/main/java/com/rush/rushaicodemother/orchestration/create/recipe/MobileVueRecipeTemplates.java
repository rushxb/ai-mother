package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.escape;

/** 渲染移动 Vue 模板有效负载。 */
@Component
final class MobileVueRecipeTemplates {

    String mobileHomeView() {
        return """
                <template>
                  <MobileHomePage />
                </template>

                <script setup lang="ts">
                import MobileHomePage from '@/pages/MobileHomePage.vue'
                </script>
                """;
    }

    /**
     * 生成移动端页面实际消费的唯一业务数据源。
     *
     * <p>字段与移动首页、分类、订单、个人中心和主布局的 import 契约保持一致；
     * 新增页面能力时应先扩展这里的运行时契约，再由 renderer 声明对应 slot 覆盖。</p>
     */
    String mobileRuntimeData(BasicRecipe recipe) {
        return """
                export const themeVars = {
                  primaryColor: '%s',
                  buttonPrimaryBackground: '%s',
                  navBarBackground: '#fffaf5',
                  navBarTitleTextColor: '#1f1a17',
                  tabsBottomBarColor: '%s'
                }

                export const tabs = [
                  { label: '首页', path: '/', icon: '⌂' },
                  { label: '分类', path: '/category', icon: '◫' },
                  { label: '订单', path: '/orders', icon: '▣' },
                  { label: '我的', path: '/profile', icon: '◎' }
                ]

                export const banners = [
                  { title: '%s', desc: '%s，为你推荐适合的%s内容' },
                  { title: '%s精选', desc: '热门%s与限时体验活动' },
                  { title: '会员专享', desc: '加入会员，持续解锁更多%s权益' }
                ]

                export const quickEntries = [
                  { title: '%s推荐', icon: '✨' },
                  { title: '新人专区', icon: '🎁' },
                  { title: '热门活动', icon: '🔥' },
                  { title: '预约服务', icon: '📅' },
                  { title: '会员中心', icon: '💳' },
                  { title: '优惠权益', icon: '🎫' },
                  { title: '订单进度', icon: '🚚' },
                  { title: '客服帮助', icon: '🎧' }
                ]

                export const productSections = [
                  {
                    title: '热门%s',
                    items: [
                      { id: 1, name: '%s入门体验', price: 49, tag: '新人推荐' },
                      { id: 2, name: '%s进阶方案', price: 99, tag: '本周热门' },
                      { id: 3, name: '%s专属服务', price: 199, tag: '会员专享' }
                    ]
                  },
                  {
                    title: '限时活动',
                    items: [
                      { id: 4, name: '%s组合包', price: 129, originalPrice: 169, tag: '限时优惠' },
                      { id: 5, name: '%s体验课', price: 29.9, tag: '立即预约' }
                    ]
                  }
                ]

                export const categories = [
                  { title: '推荐', count: 12 },
                  { title: '%s分类', count: 18 },
                  { title: '热门活动', count: 8 },
                  { title: '会员专享', count: 6 }
                ]

                export const orderSteps = ['已下单', '处理中', '服务中', '已完成']

                export const orders = [
                  { id: 'FP20260824001', title: '%s入门体验', status: 2, amount: 49, eta: '今天 18:20 前完成' },
                  { id: 'FP20260823009', title: '%s进阶方案', status: 3, amount: 99, eta: '已完成' }
                ]

                export const profileCards = [
                  { title: '优惠券', value: '6 张可用' },
                  { title: '会员积分', value: '2,480' },
                  { title: '已购%s', value: '8 项' }
                ]
                """.formatted(
                recipe.primary(),
                recipe.primary(),
                recipe.accent(),
                escape(recipe.headline()),
                escape(recipe.brand()),
                escape(recipe.entityLabel()),
                escape(recipe.brand()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel())
        );
    }

    String mobileThemeCss(BasicRecipe recipe) {
        return """
                :root {
                  --color-primary: %s;
                  --color-bg: #f7f8fa;
                  --color-accent: %s;
                  --tabbar-height: 50px;
                  --safe-area-bottom: env(safe-area-inset-bottom);
                }
                """.formatted(recipe.primary(), recipe.accent());
    }
}
