package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

import java.util.List;

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

    String mobileMockData(BasicRecipe recipe) {
        return """
                export interface Banner {
                  id: number
                  image: string
                  title: string
                  link?: string
                }

                export interface Product {
                  id: number
                  name: string
                  price: number
                  originalPrice?: number
                  image: string
                  description?: string
                  tags?: string[]
                }

                export interface Category {
                  id: number
                  name: string
                  icon?: string
                }

                export interface TabItem {
                  name: string
                  title: string
                  icon: string
                  path: string
                }

                export const banners: Banner[] = [
                  { id: 1, image: '/banner1.jpg', title: '%s' },
                  { id: 2, image: '/banner2.jpg', title: '%s精选推荐' },
                  { id: 3, image: '/banner3.jpg', title: '限时体验活动' }
                ]

                export const categories: Category[] = [
                  { id: 1, name: '%s', icon: 'apps-o' },
                  { id: 2, name: '推荐', icon: 'gift-o' },
                  { id: 3, name: '热门', icon: 'hot-o' },
                  { id: 4, name: '新品', icon: 'new-o' },
                  { id: 5, name: '会员', icon: 'star-o' },
                  { id: 6, name: '全部', icon: 'ellipsis' }
                ]

                export const products: Product[] = [
                  { id: 1, name: '%s基础服务', price: 99, originalPrice: 199, image: '/product1.jpg', tags: ['推荐', '热销'] },
                  { id: 2, name: '%s增长方案', price: 199, image: '/product2.jpg', tags: ['精选'] },
                  { id: 3, name: '%s定制包', price: 299, originalPrice: 399, image: '/product3.jpg', tags: ['专业'] },
                  { id: 4, name: '%s体验课', price: 49, image: '/product4.jpg', tags: ['限时'] }
                ]

                export const tabbar: TabItem[] = [
                  { name: 'home', title: '首页', icon: 'home-o', path: '/' },
                  { name: 'category', title: '分类', icon: 'apps-o', path: '/category' },
                  { name: 'orders', title: '订单', icon: 'orders-o', path: '/orders' },
                  { name: 'profile', title: '我的', icon: 'user-o', path: '/profile' }
                ]
                """.formatted(
                escape(recipe.headline()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel())
        );
    }

    String mobileTabbar() {
        return """
                export const tabbar = [
                  { name: 'home', title: '首页', icon: 'home-o' },
                  { name: 'category', title: '分类', icon: 'apps-o' },
                  { name: 'orders', title: '订单', icon: 'orders-o' },
                  { name: 'profile', title: '我的', icon: 'user-o' }
                ]
                """;
    }

    String mobileProducts(BasicRecipe recipe) {
        return """
                export const products = [
                  { id: 1, name: '%s基础服务', price: 99, image: '/product1.jpg', tags: ['推荐'] },
                  { id: 2, name: '%s进阶方案', price: 199, image: '/product2.jpg', tags: ['热门'] },
                  { id: 3, name: '%s定制服务', price: 299, image: '/product3.jpg', tags: ['专业'] }
                ]
                """.formatted(escape(recipe.entityLabel()), escape(recipe.entityLabel()), escape(recipe.entityLabel()));
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
