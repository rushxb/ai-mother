---
name: frontend-design
description: Create distinctive, production-grade frontend interfaces with high design quality. Use this skill when the user asks to build web components, pages, or applications. Prefer this skill for AI-generated frontend projects, especially Vue 3 + Nuxt 3 builds with motion, typography, and layout polish.
license: Complete terms in LICENSE.txt
---

This skill guides creation of distinctive, production-grade frontend interfaces that avoid generic "AI slop" aesthetics. Implement real working code with exceptional attention to aesthetic details and creative choices.

The user provides frontend requirements: a component, page, application, or interface to build. They may include context about the purpose, audience, or technical constraints.

## Default Frontend Stack

When the user does not specify a different stack, build with:
- Vue 3 + Nuxt 3
- Nuxt page transitions for route changes
- Framer Motion for Vue for弹窗、菜单、抽屉、tooltip 等需要弹性和物理感的交互
- Lenis globally enabled for smooth iOS-like scrolling
- Lucide Icons for all iconography unless the project already standardizes another set
- Tailwind CSS for utility-driven styling
- Google Fonts API for typography, always using at least one characterful headline font
- GSAP from the CDN when timeline choreography or scene-level motion is needed

If the project already has a different frontend stack, adapt these design principles to the existing architecture instead of forcing a rewrite.

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
- Prefer Nuxt page transitions instead of ad hoc route animations.
- Use Framer Motion for Vue for弹窗、下拉菜单、抽屉、tooltip 等需要物理感的交互。
- Keep Lenis enabled globally for scroll behavior on all pages.
- Use Lucide Icons by default, with clean stroke-based treatment and minimal visual weight.
- Load fonts from Google Fonts and pair one distinctive display face with a readable body face.
- Use GSAP only where timeline control adds clear value; do not replace simpler motion with GSAP out of habit.
- Prefer Tailwind CSS for layout, spacing, and responsive composition unless the project already has a different system.

## Frontend Aesthetics Guidelines

Focus on:
- **Typography**: Choose fonts that are beautiful, unique, and interesting. Avoid generic fonts like Arial and Inter; opt instead for distinctive choices that elevate the frontend's aesthetics; unexpected, characterful font choices. Pair a distinctive display font with a refined body font.
- **Color & Theme**: Commit to a cohesive aesthetic. Use CSS variables for consistency. Dominant colors with sharp accents outperform timid, evenly-distributed palettes.
- **Motion**: Use animations for effects and micro-interactions. Prioritize CSS-only solutions for HTML. Use Motion library for React when available. Focus on high-impact moments: one well-orchestrated page load with staggered reveals (animation-delay) creates more delight than scattered micro-interactions. Use scroll-triggering and hover states that surprise.
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
- A global scroll treatment
- An intentional typography system from Google Fonts
- Iconography from Lucide
- Responsive layouts that hold up on desktop and mobile
- Empty, loading, and error states where the UI implies data flow

Avoid adding decorative complexity that does not support the experience. Motion should feel structural, not pasted on.
