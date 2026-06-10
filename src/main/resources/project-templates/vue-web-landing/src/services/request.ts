import axios from 'axios'

const request = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000
})

request.interceptors.response.use(
  (response) => response.data,
  (error) => Promise.reject(error)
)

export default request
