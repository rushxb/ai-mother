package com.rush.rushaicodemother.orchestration.create.recipe;

import org.springframework.stereotype.Component;

import java.util.List;

import static com.rush.rushaicodemother.orchestration.create.recipe.RecipeValueSupport.escape;

/** Renders the desktop/basic Vue template payloads. */
@Component
final class BasicVueRecipeTemplates {

    String basicHomeView() {
        return """
                <template>
                  <section id="hero" class="hero">
                    <div class="hero-copy">
                      <span class="eyebrow">{{ hero.eyebrow }}</span>
                      <h1>{{ hero.title }}</h1>
                      <p>{{ hero.description }}</p>
                      <div class="hero-actions">
                        <a v-for="action in hero.actions" :key="action.label" :class="['btn', action.type]" :href="action.path">
                          {{ action.label }}
                        </a>
                      </div>
                    </div>
                    <div class="hero-panel">
                      <div v-for="item in features" :key="item.title" class="metric-card">
                        <strong>{{ item.stat }}</strong>
                        <span>{{ item.title }}</span>
                        <p>{{ item.text }}</p>
                      </div>
                    </div>
                  </section>

                  <section id="modules" class="content-band">
                    <SectionTitle eyebrow="Modules" title="业务模块" description="围绕真实领域模型组织页面、数据和行动路径。" />
                    <div class="card-grid">
                      <article v-for="card in cards" :key="card.id" class="feature-card">
                        <span>{{ card.id }}</span>
                        <h3>{{ card.title }}</h3>
                        <p>{{ card.desc }}</p>
                      </article>
                    </div>
                  </section>

                  <section id="timeline" class="split-section">
                    <SectionTitle title="交付流程" description="从需求定义到上线验证，每一步都可以继续在 EDIT 模式精修。" />
                    <ol class="timeline">
                      <li v-for="(item, index) in timeline" :key="item">
                        <span>{{ String(index + 1).padStart(2, '0') }}</span>
                        {{ item }}
                      </li>
                    </ol>
                  </section>
                </template>

                <script setup lang="ts">
                import SectionTitle from '@/components/SectionTitle.vue'
                import { cards, features, hero, timeline } from '@/data/siteData'
                </script>
                """;
    }

    String basicSiteData(BasicRecipe recipe) {
        return """
                export interface SiteInfo {
                  brand: string
                  slogan: string
                  nav: NavItem[]
                }

                export interface NavItem {
                  label: string
                  path: string
                  icon?: string
                }

                export interface HeroData {
                  eyebrow: string
                  title: string
                  description: string
                  actions: HeroAction[]
                }

                export interface HeroAction {
                  label: string
                  path: string
                  type: 'primary' | 'secondary'
                }

                export interface FeatureItem {
                  title: string
                  text: string
                  stat: string
                }

                export interface CardItem {
                  id: string
                  title: string
                  desc: string
                }

                export const site: SiteInfo = {
                  brand: '%s',
                  slogan: '%s',
                  nav: [
                    { label: '首页', path: '#hero' },
                    { label: '模块', path: '#modules' },
                    { label: '流程', path: '#timeline' },
                    { label: '联系', path: '#contact' }
                  ]
                }

                export const hero: HeroData = {
                  eyebrow: '%s',
                  title: '%s',
                  description: '%s',
                  actions: [
                    { label: '查看模块', path: '#modules', type: 'primary' },
                    { label: '联系咨询', path: '#contact', type: 'secondary' }
                  ]
                }

                export const features: FeatureItem[] = [
                  { title: '领域建模', text: '围绕%s沉淀实体、字段和页面结构。', stat: 'Spec' },
                  { title: '稳定生成', text: '代码由本地 recipe 渲染，首轮生成更稳定。', stat: 'Recipe' },
                  { title: '持续增强', text: '后续可继续用 EDIT 精修页面和交互。', stat: 'Edit' }
                ]

                export const cards: CardItem[] = [
                  { id: 'core', title: '%s核心流程', desc: '把关键业务动作整理成清晰的页面路径。' },
                  { id: 'data', title: '数据样例', desc: '预置贴合行业的 mock 数据，便于立即预览。' },
                  { id: 'growth', title: '增长入口', desc: '保留咨询、转化或下一步行动的扩展空间。' }
                ]

                export const timeline: string[] = [
                  '理解需求并生成创意规格',
                  '本地 recipe 渲染稳定代码',
                  '执行校验和构建',
                  '进入预览并继续增强'
                ]
                """.formatted(
                escape(recipe.brand()),
                escape(recipe.headline()),
                escape(recipe.domain()),
                escape(recipe.headline()),
                escape(recipe.description()),
                escape(recipe.entityLabel()),
                escape(recipe.entityLabel())
        );
    }

    String basicAppConfig(BasicRecipe recipe) {
        return """
                export const appConfig = {
                  title: '%s',
                  description: '%s',
                  copyright: '© 2026 %s'
                }
                """.formatted(escape(recipe.brand()), escape(recipe.description()), escape(recipe.brand()));
    }

    String basicNavigation() {
        return """
                export const navItems = [
                  { label: '首页', path: '#hero' },
                  { label: '模块', path: '#modules' },
                  { label: '流程', path: '#timeline' },
                  { label: '联系', path: '#contact' }
                ]
                """;
    }

    String basicThemeCss(BasicRecipe recipe) {
        return """
                :root {
                  --color-primary: %s;
                  --color-secondary: %s;
                  --color-bg: #ffffff;
                  --color-text: #111827;
                  --spacing-md: 1rem;
                  --radius-md: 0.5rem;
                }
                """.formatted(recipe.primary(), recipe.accent());
    }
}
