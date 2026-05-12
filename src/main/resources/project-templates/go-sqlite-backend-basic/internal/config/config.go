package config

import (
	"os"
)

type Config struct {
	Addr       string
	DatabaseDSN string
}

func Load() Config {
	return Config{
		Addr:        envOrDefault("SERVER_ADDR", ":18000"),
		DatabaseDSN: envOrDefault("DATABASE_DSN", "data/app.db"),
	}
}

func envOrDefault(key string, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
