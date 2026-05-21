import type { App } from 'vue'
import { BlurReveal } from './blur-reveal'
import { BoxReveal } from './box-reveal'
import { BubblesBackground } from './bubbles-background'
import { MorphingTabs } from './morphing-tabs'
import { ShimmerButton } from './shimmer-button'
import { TextGenerateEffect } from './text-generate-effect'

const inspiraUiComponents = {
  BlurReveal,
  BoxReveal,
  BubblesBackground,
  MorphingTabs,
  ShimmerButton,
  TextGenerateEffect,
}

export {
  BlurReveal,
  BoxReveal,
  BubblesBackground,
  MorphingTabs,
  ShimmerButton,
  TextGenerateEffect,
  inspiraUiComponents,
}

export default {
  install(app: App) {
    Object.entries(inspiraUiComponents).forEach(([name, component]) => {
      app.component(name, component)
    })
  },
}
