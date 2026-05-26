/**
 * Reusable motion-v animation presets for consistent, premium-feeling motion.
 * Design philosophy: subtle, quick, physics-based — "unnoticed but felt".
 */

/** Standard fade-up entrance for sections and cards */
export const fadeUp = (delay = 0) => ({
  initial: { opacity: 0, y: 16 },
  whileInView: { opacity: 1, y: 0 },
  inViewOptions: { once: true, margin: '-8% 0px' },
  transition: { duration: 0.52, ease: [0.25, 0.46, 0.45, 0.94], delay },
})

/** Staggered children — pass index, returns props for each child */
export const staggerChild = (index: number, baseDelay = 0) => ({
  initial: { opacity: 0, y: 14 },
  whileInView: { opacity: 1, y: 0 },
  inViewOptions: { once: true, margin: '-6% 0px' },
  transition: {
    duration: 0.48,
    ease: [0.25, 0.46, 0.45, 0.94],
    delay: baseDelay + index * 0.07,
  },
})

/** Fade-in from specific direction */
export const fadeFrom = (
  direction: 'left' | 'right' | 'up' | 'down' = 'up',
  delay = 0,
) => {
  const offsets = { left: { x: -16 }, right: { x: 16 }, up: { y: 14 }, down: { y: -14 } }
  return {
    initial: { opacity: 0, ...offsets[direction] },
    whileInView: { opacity: 1, x: 0, y: 0 },
    inViewOptions: { once: true, margin: '-8% 0px' },
    transition: { duration: 0.5, ease: [0.25, 0.46, 0.45, 0.94], delay },
  }
}

/** Scale-in entrance (for modals, popovers, emphasis elements) */
export const scaleIn = (delay = 0) => ({
  initial: { opacity: 0, scale: 0.92 },
  whileInView: { opacity: 1, scale: 1 },
  inViewOptions: { once: true, margin: '-6% 0px' },
  transition: { duration: 0.45, ease: [0.34, 1.56, 0.64, 1], delay },
})

/** Spring-based hover lift — merge into @mouseenter props */
export const hoverLift = {
  whileHover: { y: -3, transition: { type: 'spring', stiffness: 400, damping: 25 } },
  whileTap: { scale: 0.985, transition: { type: 'spring', stiffness: 400, damping: 25 } },
}

/** Subtle press feedback */
export const tapPress = {
  whileTap: { scale: 0.97, transition: { type: 'spring', stiffness: 500, damping: 30 } },
}

/** Route transition variants for AnimatePresence */
export const routeTransition = {
  initial: { opacity: 0, y: 8, filter: 'blur(4px)' },
  animate: { opacity: 1, y: 0, filter: 'blur(0px)' },
  exit: { opacity: 0, y: -6, filter: 'blur(3px)' },
  transition: { duration: 0.32, ease: [0.25, 0.46, 0.45, 0.94] },
}
