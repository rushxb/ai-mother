---
name: frontend-design
description: Create distinctive, production-grade frontend interfaces with high design quality. Use this skill when the user asks to build web components, pages, or applications. Optimized for Vue 3 + Vite builds with Inspira UI, shadcn-vue, motion-v, TresJS 3D, and Lenis smooth scrolling.
keywords: frontend,前端,网页,网站,页面,ui,ux,design,视觉,动效,交互动效,官网,landing,product,page,vue,vue3,vite,tailwind,inspira,shadcn,tresjs,motion,lenis,3d
modules: ui,layout,motion,typography,visual-design,3d,particles
contextFileHints: src/components,src/components/ui,src/components/inspira,src/components/tres,src/pages,src/views,src/layouts,src/styles,src/assets,src/lib,app.vue
implementationHints: 优先使用 Inspira UI 实现炫酷视觉效果；使用 shadcn-vue 作为基础 UI 组件；使用 TresJS 实现 3D 场景和交互；使用 motion-v 实现物理动画；使用 Lenis 实现丝滑滚动；优先使用具有个性的标题字体；默认把视觉完成度和空间层级放在首位；图标默认使用 Lucide；样式优先 Tailwind
validationHints: 检查响应式布局；检查页面切换动画；检查 Lenis 滚动是否全局启用；检查 TresJS 3D 场景是否正确渲染；检查 Inspira UI 组件动画效果；检查字体和图标是否按默认栈加载；检查弹窗与菜单的物理感交互是否自然
license: Complete terms in LICENSE.txt
---

This skill guides creation of distinctive, production-grade frontend interfaces that avoid generic "AI slop" aesthetics. Implement real working code with exceptional attention to aesthetic details and creative choices.

The user provides frontend requirements: a component, page, application, or interface to build. They may include context about the purpose, audience, or technical constraints.

## Default Frontend Stack

When the user does not specify a different stack, build with:
- **Vue 3 + Vite** as the core framework
- **Inspira UI** (primary) for bold, creative visual effects and animations
- **shadcn-vue** (fallback/base) for headless, accessible UI primitives (Radix Vue based)
- **motion-v** for physics-based animations (弹窗、菜单、抽屉、tooltip 等需要弹性和物理感的交互)
- **TresJS** for 3D scenes, cinematic interactions, and immersive visual experiences
- **Lenis** globally enabled for smooth iOS-like scrolling
- **Lucide Icons** for all iconography unless the project already standardizes another set
- **Tailwind CSS** for utility-driven styling
- **Google Fonts API** for typography, always using at least one characterful headline font

If the project already has a different frontend stack, adapt these design principles to the existing architecture instead of forcing a rewrite.

## Component Architecture

### Component Library Hierarchy

```
src/components/
├── ui/              # shadcn-vue 基础组件 (Button, Card, Dialog, Input, Label, Select, Badge)
├── inspira/         # Inspira UI 炫酷组件 (GradientText, AnimateOnScroll, ParticleField, TypewriterEffect, GlowingOrb)
├── tres/            # TresJS 3D 组件
│   ├── effects/     # 视觉特效 (BloomEffect, GlassRefraction, VolumetricLight, MatcapRendering, EnvironmentMapping)
│   ├── interactive/ # 交互维度 (ScrollDriven, MouseParallax, RaycasterInteraction, GravityPhysics, CameraTransition)
│   ├── particles/   # 创意粒子 (ParticleSwarm, Morphing, InstancedMesh, LiquidShader, Typography3D)
│   └── post-processing/ # 后处理艺术 (GlitchEffect, DepthOfField, ASCIIEffect, MotionBlur, ChromaticAberration)
└── drawer/          # vaul-vue 移动端抽屉组件
```

### When to Use Each Library

| 需求场景 | 推荐组件库 | 示例 |
|---------|-----------|------|
| 基础表单、按钮、卡片 | shadcn-vue | Button, Card, Input, Select |
| 炫酷标题、动画文字 | Inspira UI | GradientText, TypewriterEffect |
| 滚动触发动画 | Inspira UI + motion-v | AnimateOnScroll |
| 粒子背景、光效 | Inspira UI | ParticleField, GlowingOrb |
| 3D 场景、物体展示 | TresJS | BasicScene, RotatingCube |
| 电影级交互、滚动驱动 | TresJS | ScrollDriven, CameraTransition |
| 视觉特效、后处理 | TresJS | BloomEffect, GlitchEffect, DepthOfField |
| 物理动画、弹性交互 | motion-v | 弹窗、菜单、抽屉动画 |
| 丝滑滚动体验 | Lenis | 全局滚动平滑 |

## Design Thinking

Before coding, understand the context and commit to a BOLD aesthetic direction:
- **Purpose**: What problem does this interface solve? Who uses it?
- **Tone**: Pick an extreme: brutally minimal, maximalist chaos, retro-futuristic, organic/natural, luxury/refined, playful/toy-like, editorial/magazine, brutalist/raw, art deco/geometric, soft/pastel, industrial/utilitarian, etc. There are so many flavors to choose from. Use these for inspiration but design one that is true to the aesthetic direction.
- **Constraints**: Technical requirements (framework, performance, accessibility).
- **Differentiation**: What makes this UNFORGETTABLE? What's the one thing someone will remember?

**CRITICAL**: Choose a clear conceptual direction and execute it with precision. Bold maximalism and refined minimalism both work - the key is intentionality, not intensity.

Then implement working code (HTML/CSS/JS, React, Vue, etc.) that is:
- Production-grade and functional
- Visually striking and memorable
- Cohesive with a clear aesthetic point-of-view
- Meticulously refined in every detail

For generated frontend projects, treat these as hard preferences unless they conflict with the user's request or existing codebase:
- Use **Inspira UI** for bold visual effects, animations, and creative components.
- Use **shadcn-vue** for accessible, headless base components (Dialog, Popover, Select, etc.).
- Use **TresJS** for 3D scenes and cinematic interactions.
- Use **motion-v** for physics-based animations on overlays, menus, and tooltips.
- Keep **Lenis** enabled globally for scroll behavior on all pages.
- Use **Lucide Icons** by default, with clean stroke-based treatment and minimal visual weight.
- Load fonts from Google Fonts and pair one distinctive display face with a readable body face.
- Prefer **Tailwind CSS** for layout, spacing, and responsive composition unless the project already has a different system.

## Frontend Aesthetics Guidelines

Focus on:
- **Typography**: Choose fonts that are beautiful, unique, and interesting. Avoid generic fonts like Arial and Inter; opt instead for distinctive choices that elevate the frontend's aesthetics; unexpected, characterful font choices. Pair a distinctive display font with a refined body font.
- **Color & Theme**: Commit to a cohesive aesthetic. Use CSS variables for consistency. Dominant colors with sharp accents outperform timid, evenly-distributed palettes.
- **Motion**: Use animations for effects and micro-interactions. Prioritize CSS-only solutions for HTML. Use **motion-v** for React when available. Focus on high-impact moments: one well-orchestrated page load with staggered reveals (animation-delay) creates more delight than scattered micro-interactions. Use scroll-triggering and hover states that surprise.
- **3D & Immersion**: Use **TresJS** to break the flat plane — create cinematic 3D interactions, scroll-driven camera movements, particle systems, and post-processing effects. Transform every touch/click into seamless visual flow.
- **Spatial Composition**: Unexpected layouts. Asymmetry. Overlap. Diagonal flow. Grid-breaking elements. Generous negative space OR controlled density.
- **Backgrounds & Visual Details**: Create atmosphere and depth rather than defaulting to solid colors. Add contextual effects and textures that match the overall aesthetic. Apply creative forms like gradient meshes, noise textures, geometric patterns, layered transparencies, dramatic shadows, decorative borders, custom cursors, and grain overlays.

NEVER use generic AI-generated aesthetics like overused font families (Inter, Roboto, Arial, system fonts), cliched color schemes (particularly purple gradients on white backgrounds), predictable layouts and component patterns, and cookie-cutter design that lacks context-specific character.

Interpret creatively and make unexpected choices that feel genuinely designed for the context. No design should be the same. Vary between light and dark themes, different fonts, different aesthetics. NEVER converge on common choices (Space Grotesk, for example) across generations.

**IMPORTANT**: Match implementation complexity to the aesthetic vision. Maximalist designs need elaborate code with extensive animations and effects. Minimalist or refined designs need restraint, precision, and careful attention to spacing, typography, and subtle details. Elegance comes from executing the vision well.

Remember: Claude is capable of extraordinary creative work. Don't hold back, show what can truly be created when thinking outside the box and committing fully to a distinctive vision.

## Generated App Baseline

For generated apps, the first pass should usually include:
- A real working navigation structure
- Page transitions and at least one motion layer for overlays or menus
- A global scroll treatment with **Lenis**
- An intentional typography system from Google Fonts
- Iconography from Lucide
- Responsive layouts that hold up on desktop and mobile
- Empty, loading, and error states where the UI implies data flow
- At least one **Inspira UI** component for visual flair (e.g., GradientText, AnimateOnScroll)
- Consider **TresJS** for hero sections or immersive 3D experiences

Avoid adding decorative complexity that does not support the experience. Motion should feel structural, not pasted on.

## TresJS 3D Guidelines

When using TresJS for 3D experiences:

### Visual Effects (effects/)
- **BloomEffect**: Neon glow, cyberpunk aesthetics
- **GlassRefraction**: Frosted glass, lens effects
- **VolumetricLight**: God rays, mysterious atmospheres
- **MatcapRendering**: Metal, ceramic, silk materials
- **EnvironmentMapping**: Reflective surfaces

### Interactive Experience (interactive/)
- **ScrollDriven**: Camera follows scroll, cinematic storytelling
- **MouseParallax**: 3D scene responds to mouse movement
- **RaycasterInteraction**: Click/hover on 3D objects
- **GravityPhysics**: Elements fall, bounce, stack
- **CameraTransition**: Smooth camera flight between pages

### Creative Particles (particles/)
- **ParticleSwarm**: Thousands of flowing particles
- **Morphing**: Shape-to-shape transitions
- **InstancedMesh**: High-performance object clusters
- **LiquidShader**: Lava, water, organic movement
- **Typography3D**: 3D text with depth and shadow

### Post-Processing Art (post-processing/)
- **GlitchEffect**: Glitch art, edgy aesthetics
- **DepthOfField**: Cinematic focus, macro effect
- **ASCIIEffect**: Matrix/code aesthetic
- **MotionBlur**: Smooth high-speed motion
- **ChromaticAberration**: Film-like color fringing

**Performance Note**: Use 3D effects judiciously. Not every page needs 3D. Focus on hero sections, key interactions, and moments that benefit from depth and immersion. Always consider mobile performance.
