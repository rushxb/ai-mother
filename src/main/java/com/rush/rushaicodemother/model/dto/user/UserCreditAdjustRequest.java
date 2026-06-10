package com.rush.rushaicodemother.model.dto.user;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserCreditAdjustRequest implements Serializable {

    private Long userId;

    private Long changeAmount;

    private String remark;

    private static final long serialVersionUID = 1L;
}
