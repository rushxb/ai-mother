import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import type { Product } from '@/data/mock'

export interface CartItem {
  product: Product
  quantity: number
}

export const useCartStore = defineStore('cart', () => {
  // State
  const items = ref<CartItem[]>([])
  
  // Getters
  const totalCount = computed(() => items.value.reduce((sum, item) => sum + item.quantity, 0))
  
  const totalPrice = computed(() => 
    items.value.reduce((sum, item) => sum + item.product.price * item.quantity, 0)
  )
  
  // Actions
  function addItem(product: Product, quantity = 1) {
    const existing = items.value.find((item) => item.product.id === product.id)
    if (existing) {
      existing.quantity += quantity
    } else {
      items.value.push({ product, quantity })
    }
  }
  
  function removeItem(productId: number) {
    const index = items.value.findIndex((item) => item.product.id === productId)
    if (index > -1) {
      items.value.splice(index, 1)
    }
  }
  
  function updateQuantity(productId: number, quantity: number) {
    const item = items.value.find((item) => item.product.id === productId)
    if (item) {
      item.quantity = quantity
    }
  }
  
  function clearCart() {
    items.value = []
  }
  
  // @AI_INJECT_STORE_ACTION
  
  return {
    items,
    totalCount,
    totalPrice,
    addItem,
    removeItem,
    updateQuantity,
    clearCart
  }
}, {
  persist: {
    key: 'cart-store',
    storage: localStorage,
    paths: ['items']
  }
})
