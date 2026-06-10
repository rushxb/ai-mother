<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRenderLoop } from '@tresjs/core'
import { InstancedMesh, Object3D } from 'three'
import { cn } from '@/lib/utils'

interface Props {
  class?: string
  count?: number
  color?: string
  size?: number
}

const props = withDefaults(defineProps<Props>(), {
  count: 1000,
  color: '#00ff00',
  size: 0.1,
})

const meshRef = ref<InstancedMesh>()
const dummy = new Object3D()

interface Instance {
  position: { x: number; y: number; z: number }
  rotation: { x: number; y: number; z: number }
  scale: number
  phase: number
}

const instances = ref<Instance[]>([])

onMounted(() => {
  instances.value = Array.from({ length: count }, () => ({
    position: {
      x: (Math.random() - 0.5) * 10,
      y: (Math.random() - 0.5) * 10,
      z: (Math.random() - 0.5) * 10,
    },
    rotation: {
      x: Math.random() * Math.PI,
      y: Math.random() * Math.PI,
      z: Math.random() * Math.PI,
    },
    scale: Math.random() * 0.5 + 0.5,
    phase: Math.random() * Math.PI * 2,
  }))
})

const { onLoop } = useRenderLoop()

onLoop(({ elapsed }) => {
  if (meshRef.value) {
    instances.value.forEach((instance, i) => {
      dummy.position.set(
        instance.position.x + Math.sin(elapsed + instance.phase) * 0.5,
        instance.position.y + Math.cos(elapsed + instance.phase) * 0.5,
        instance.position.z
      )
      dummy.rotation.set(
        instance.rotation.x + elapsed * 0.5,
        instance.rotation.y + elapsed * 0.3,
        instance.rotation.z
      )
      dummy.scale.setScalar(instance.scale * size)
      dummy.updateMatrix()
      meshRef.value.setMatrixAt(i, dummy.matrix)
    })
    meshRef.value.instanceMatrix.needsUpdate = true
  }
})
</script>

<template>
  <div :class="cn('w-full h-full', props.class)">
    <TresCanvas>
      <TresPerspectiveCamera :position="[0, 0, 15]" />
      <TresAmbientLight :intensity="0.5" />
      <TresDirectionalLight :position="[10, 10, 10]" :intensity="1" />
      <TresInstancedMesh ref="meshRef" :args="[null, null, count]">
        <TresBoxGeometry :args="[1, 1, 1]" />
        <TresMeshStandardMaterial :color="color" />
      </TresInstancedMesh>
      <slot />
    </TresCanvas>
  </div>
</template>
