import type { App } from 'vue'
import { BlurReveal } from './blur-reveal'
import { BoxReveal } from './box-reveal'
import { BubblesBackground } from './bubbles-background'
import { AppleBlurImage, AppleCard, AppleCardCarousel, AppleCarouselItem } from './apple-card-carousel'
import { DirectionAwareHover } from './direction-aware-hover'
import { GlowingEffect } from './glowing-effect'
import { Lens } from './lens'
import { MorphingTabs } from './morphing-tabs'
import { ShimmerButton } from './shimmer-button'
import { TextGenerateEffect } from './text-generate-effect'
import { TextScrollReveal } from './text-scroll-reveal'

const inspiraUiComponents = {
  BlurReveal,
  BoxReveal,
  BubblesBackground,
  AppleBlurImage,
  AppleCard,
  AppleCardCarousel,
  AppleCarouselItem,
  DirectionAwareHover,
  GlowingEffect,
  Lens,
  MorphingTabs,
  ShimmerButton,
  TextGenerateEffect,
  TextScrollReveal,
}

export {
  BlurReveal,
  BoxReveal,
  BubblesBackground,
  AppleBlurImage,
  AppleCard,
  AppleCardCarousel,
  AppleCarouselItem,
  DirectionAwareHover,
  GlowingEffect,
  Lens,
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
