package com.powerbill.identity;

import java.util.UUID;

import com.powerbill.common.RequestContext;

public interface CustomerIdentity {
    UUID registerCustomer(String email, String password, String fullName, String mobileNumber, RequestContext request);

    UUID provisionCustomer(String email, String password, String fullName, String mobileNumber, RequestContext request);

    UUID seedDemoCustomer(String email, String password, String fullName, String mobileNumber);
}
