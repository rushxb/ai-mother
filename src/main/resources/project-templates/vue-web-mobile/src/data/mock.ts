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
  { id: 1, image: '/banner1.jpg', title: '新品上市' },
  { id: 2, image: '/banner2.jpg', title: '限时优惠' },
  { id: 3, image: '/banner3.jpg', title: '品牌活动' }
]

export const categories: Category[] = [
  { id: 1, name: '分类一', icon: 'apps-o' },
  { id: 2, name: '分类二', icon: 'gift-o' },
  { id: 3, name: '分类三', icon: 'hot-o' },
  { id: 4, name: '分类四', icon: 'new-o' },
  { id: 5, name: '分类五', icon: 'star-o' },
  { id: 6, name: '分类六', icon: 'fire-o' },
  { id: 7, name: '分类七', icon: 'like-o' },
  { id: 8, name: '全部', icon: 'ellipsis' }
]

export const products: Product[] = [
  { id: 1, name: '商品名称 1', price: 99, originalPrice: 199, image: '/product1.jpg', tags: ['热销', '新品'] },
  { id: 2, name: '商品名称 2', price: 199, image: '/product2.jpg', tags: ['推荐'] },
  { id: 3, name: '商品名称 3', price: 299, originalPrice: 399, image: '/product3.jpg' },
  { id: 4, name: '商品名称 4', price: 399, image: '/product4.jpg', tags: ['限量'] },
  { id: 5, name: '商品名称 5', price: 499, image: '/product5.jpg' },
  { id: 6, name: '商品名称 6', price: 599, originalPrice: 699, image: '/product6.jpg', tags: ['特惠'] }
]

export const tabbar: TabItem[] = [
  { name: 'home', title: '首页', icon: 'home-o', path: '/' },
  { name: 'category', title: '分类', icon: 'apps-o', path: '/category' },
  { name: 'cart', title: '购物车', icon: 'shopping-cart-o', path: '/cart' },
  { name: 'user', title: '我的', icon: 'user-o', path: '/user' }
]
