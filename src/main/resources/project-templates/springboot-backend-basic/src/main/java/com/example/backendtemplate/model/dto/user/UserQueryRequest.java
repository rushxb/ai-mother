package com.example.backendtemplate.model.dto.user;

import com.example.backendtemplate.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class UserQueryRequest extends PageRequest {

    private Long id;

    private String userAccount;

    private String userName;

    private String userRole;
}
