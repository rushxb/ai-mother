import type { App } from 'vue'
import { BlurReveal } from './blur-reveal'
import { BoxReveal } from './box-reveal'
import { BubblesBackground } from './bubbles-background'
import { MorphingTabs } from './morphing-tabs'
import { ShimmerButton } from './shimmer-button'
import { TextGenerateEffect } from './text-generate-effect'
import { TextScrollReveal } from './text-scroll-reveal'

const inspiraUiComponents = {
  BlurReveal,
  BoxReveal,
  BubblesBackground,
  MorphingTabs,
  ShimmerButton,
  TextGenerateEffect,
  TextScrollReveal,
}

export {
  BlurReveal,
  BoxReveal,
  BubblesBackground,
  MorphingTabs,
  ShimmerButton,
  TextGenerateEffect,
  TextScrollReveal,
  inspiraUiComponents,
}

export default {
  install(app: App) {
    Object.entries(inspiraUiComponents).forEach(([name, component]) => {
      app.component(name, component)
    })
  },
}
