package config

import "os"

// Config 应用配置
type Config struct {
	Addr        string
	DatabaseDSN string
	LogLevel    string
}

// Load 从环境变量加载配置
func Load() Config {
	return Config{
		Addr:        envOrDefault("SERVER_ADDR", ":18000"),
		DatabaseDSN: envOrDefault("DATABASE_DSN", "data/app.db"),
		LogLevel:    envOrDefault("LOG_LEVEL", "info"),
	}
}

func envOrDefault(key string, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
