package validator

import (
	"fmt"
	"strings"

	"github.com/go-playground/validator/v10"
)

// Validator 全局校验器实例
var Validator *validator.Validate

func init() {
	Validator = validator.New()
}

// ValidateStruct 校验结构体，返回第一个错误的友好消息
func ValidateStruct(s any) error {
	err := Validator.Struct(s)
	if err == nil {
		return nil
	}
	if validationErrors, ok := err.(validator.ValidationErrors); ok {
		return fmt.Errorf("%s", translateError(validationErrors[0]))
	}
	return err
}

// ValidateStructAll 校验结构体，返回所有错误
func ValidateStructAll(s any) []string {
	err := Validator.Struct(s)
	if err == nil {
		return nil
	}
	var messages []string
	if validationErrors, ok := err.(validator.ValidationErrors); ok {
		for _, e := range validationErrors {
			messages = append(messages, translateError(e))
		}
	}
	return messages
}

func translateError(e validator.FieldError) string {
	field := e.Field()
	tag := e.Tag()
	param := e.Param()

	switch tag {
	case "required":
		return fmt.Sprintf("%s 不能为空", field)
	case "min":
		return fmt.Sprintf("%s 长度不能小于 %s", field, param)
	case "max":
		return fmt.Sprintf("%s 长度不能大于 %s", field, param)
	case "len":
		return fmt.Sprintf("%s 长度必须为 %s", field, param)
	case "email":
		return fmt.Sprintf("%s 格式不正确", field)
	case "url":
		return fmt.Sprintf("%s 必须是有效的 URL", field)
	case "oneof":
		return fmt.Sprintf("%s 必须是 [%s] 之一", field, strings.ReplaceAll(param, " ", ", "))
	case "gte":
		return fmt.Sprintf("%s 必须大于等于 %s", field, param)
	case "lte":
		return fmt.Sprintf("%s 必须小于等于 %s", field, param)
	case "gt":
		return fmt.Sprintf("%s 必须大于 %s", field, param)
	case "lt":
		return fmt.Sprintf("%s 必须小于 %s", field, param)
	default:
		return fmt.Sprintf("%s 校验失败: %s", field, tag)
	}
}
