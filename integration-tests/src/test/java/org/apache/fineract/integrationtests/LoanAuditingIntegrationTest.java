/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.integrationtests;

import static org.apache.fineract.infrastructure.core.domain.AuditableFieldsConstants.CREATED_BY;
import static org.apache.fineract.infrastructure.core.domain.AuditableFieldsConstants.CREATED_DATE;
import static org.apache.fineract.infrastructure.core.domain.AuditableFieldsConstants.LAST_MODIFIED_BY;
import static org.apache.fineract.infrastructure.core.domain.AuditableFieldsConstants.LAST_MODIFIED_DATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.fineract.infrastructure.core.service.DateUtils;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.loans.LoanApplicationTestBuilder;
import org.apache.fineract.integrationtests.common.loans.LoanProductTestBuilder;
import org.apache.fineract.integrationtests.common.loans.LoanStatusChecker;
import org.apache.fineract.integrationtests.common.loans.LoanTestLifecycleExtension;
import org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper;
import org.apache.fineract.integrationtests.common.organisation.StaffHelper;
import org.apache.fineract.integrationtests.useradministration.users.UserHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ExtendWith(LoanTestLifecycleExtension.class)
public class LoanAuditingIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(LoanAuditingIntegrationTest.class);
    private ResponseSpecification responseSpec;
    private RequestSpecification requestSpec;
    private LoanTransactionHelper loanTransactionHelper;
    private AccountHelper accountHelper;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());

        this.responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        this.loanTransactionHelper = new LoanTransactionHelper(this.requestSpec, this.responseSpec);
        this.accountHelper = new AccountHelper(this.requestSpec, this.responseSpec);
    }

    @Test
    public void checkAuditDates() throws InterruptedException {
        final Integer staffId = StaffHelper.createStaff(this.requestSpec, this.responseSpec);
        String username = Utils.uniqueRandomStringGenerator("user", 8);
        final Integer userId = (Integer) UserHelper.createUser(this.requestSpec, this.responseSpec, 1, staffId, username, "A1b2c3d4e5f$",
                "resourceId");

        LOG.info("-------------------------Creating Client---------------------------");

        final Integer clientID = ClientHelper.createClient(requestSpec, responseSpec);
        ClientHelper.verifyClientCreatedOnServer(requestSpec, responseSpec, clientID);
        LOG.info("-------------------------Creating Loan---------------------------");
        final Account assetAccount = this.accountHelper.createAssetAccount();
        final Account incomeAccount = this.accountHelper.createIncomeAccount();
        final Account expenseAccount = this.accountHelper.createExpenseAccount();
        final Account overpaymentAccount = this.accountHelper.createLiabilityAccount();

        final Integer loanProductID = this.loanTransactionHelper.createLoanProduct("0", "0", LoanProductTestBuilder.DEFAULT_STRATEGY, "2",
                assetAccount, incomeAccount, expenseAccount, overpaymentAccount);
        OffsetDateTime now = Utils.getAuditDateTimeToCompare();

        final Integer loanID = this.loanTransactionHelper.applyForLoanApplicationWithPaymentStrategyAndPastMonth(clientID, loanProductID,
                Collections.emptyList(), null, "10000", LoanApplicationTestBuilder.DEFAULT_STRATEGY, "10 July 2022", "11 July 2022");
        Assertions.assertNotNull(loanID);
        HashMap loanStatusHashMap = LoanStatusChecker.getStatusOfLoan(this.requestSpec, this.responseSpec, loanID);
        LoanStatusChecker.verifyLoanIsPending(loanStatusHashMap);

        Map<String, Object> auditFieldsResponse = LoanTransactionHelper.getLoanAuditFields(requestSpec, responseSpec, loanID, "");

        OffsetDateTime createdDate = OffsetDateTime.parse((String) auditFieldsResponse.get(CREATED_DATE),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        OffsetDateTime lastModifiedDate = OffsetDateTime.parse((String) auditFieldsResponse.get(LAST_MODIFIED_DATE),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        LOG.info("-------------------------Check Audit dates---------------------------");
        assertEquals(1, auditFieldsResponse.get(CREATED_BY));
        assertEquals(1, auditFieldsResponse.get(LAST_MODIFIED_BY));
        assertTrue(DateUtils.isEqual(now, createdDate, ChronoUnit.MINUTES));
        assertTrue(DateUtils.isEqual(now, lastModifiedDate, ChronoUnit.MINUTES));

        LOG.info("-----------------------------------APPROVE LOAN-----------------------------------------");

        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization",
                "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey(username, "A1b2c3d4e5f$"));
        this.loanTransactionHelper = new LoanTransactionHelper(this.requestSpec, this.responseSpec);

        OffsetDateTime now2 = Utils.getAuditDateTimeToCompare();
        loanStatusHashMap = this.loanTransactionHelper.approveLoan("11 July 2022", loanID);
        LoanStatusChecker.verifyLoanIsApproved(loanStatusHashMap);
        auditFieldsResponse = LoanTransactionHelper.getLoanAuditFields(requestSpec, responseSpec, loanID, "");

        OffsetDateTime createdDate2 = OffsetDateTime.parse((String) auditFieldsResponse.get(CREATED_DATE),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        lastModifiedDate = OffsetDateTime.parse((String) auditFieldsResponse.get(LAST_MODIFIED_DATE),
                DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        LOG.info("-------------------------Check Audit dates---------------------------");
        assertEquals(1, auditFieldsResponse.get(CREATED_BY));
        assertTrue(DateUtils.isEqual(now, createdDate2, ChronoUnit.MINUTES));
        assertTrue(DateUtils.isEqual(createdDate, createdDate2));

        assertEquals(userId, auditFieldsResponse.get(LAST_MODIFIED_BY));
        assertTrue(DateUtils.isEqual(now2, lastModifiedDate, ChronoUnit.MINUTES));
    }
}
