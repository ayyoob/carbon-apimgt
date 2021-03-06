/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
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

package org.wso2.carbon.apimgt.impl.workflow;


import java.sql.Connection;
import java.sql.SQLException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.AccessTokenInfo;
import org.wso2.carbon.apimgt.api.model.AccessTokenRequest;
import org.wso2.carbon.apimgt.api.model.Application;
import org.wso2.carbon.apimgt.api.model.Subscriber;
import org.wso2.carbon.apimgt.api.model.KeyManager;
import org.wso2.carbon.apimgt.api.model.OAuthApplicationInfo;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.factory.KeyManagerHolder;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.ApplicationRegistrationWorkflowDTO;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.utils.ApplicationUtils;
import org.wso2.carbon.apimgt.keymgt.client.SubscriberKeyMgtClient;

public abstract class AbstractApplicationRegistrationWorkflowExecutor extends WorkflowExecutor{

    private static final Log log = LogFactory.getLog(AbstractApplicationRegistrationWorkflowExecutor.class);
    
    public String getWorkflowType(){
       return WorkflowConstants.WF_TYPE_AM_APPLICATION_REGISTRATION_PRODUCTION;
    }

    public void execute(WorkflowDTO workFlowDTO) throws WorkflowException {
        log.debug("Executing AbstractApplicationRegistrationWorkflowExecutor...");
        ApiMgtDAO dao = new ApiMgtDAO();
        Connection conn = null;
        try {
            //dao.createApplicationRegistrationEntry((ApplicationRegistrationWorkflowDTO) workFlowDTO, false);
            ApplicationRegistrationWorkflowDTO appRegDTO = (ApplicationRegistrationWorkflowDTO)workFlowDTO;
            conn = APIMgtDBUtil.getConnection();
            dao.createApplicationRegistrationEntry(appRegDTO,false, conn);
           // appRegDTO.getAppInfoDTO().saveDTO();
            super.execute(workFlowDTO);
            APIMgtDBUtil.transactionCommit(conn);
        } catch (APIManagementException e) {
        	APIMgtDBUtil.transactionRollback(conn);
            log.error("Error while creating Application Registration entry.", e);
            throw new WorkflowException("Error while creating Application Registration entry.", e);
        } catch (SQLException e) {
        	APIMgtDBUtil.transactionRollback(conn);
        	log.error("Error while creating Application Registration entry.", e);
            throw new WorkflowException("Error while creating Application Registration entry.", e);
		}finally{
			APIMgtDBUtil.closeConnection(conn);
		}
    }

    public void complete(WorkflowDTO workFlowDTO) throws WorkflowException {
        log.debug("Completing AbstractApplicationRegistrationWorkflowExecutor...");
        ApiMgtDAO dao = new ApiMgtDAO();
        try {
            String status = null;
            if ("CREATED".equals(workFlowDTO.getStatus().toString())) {
                status = APIConstants.AppRegistrationStatus.REGISTRATION_CREATED;
            } else if ("REJECTED".equals(workFlowDTO.getStatus().toString())) {
                status = APIConstants.AppRegistrationStatus.REGISTRATION_REJECTED;
            } else if ("APPROVED".equals(workFlowDTO.getStatus().toString())) {
                status = APIConstants.AppRegistrationStatus.REGISTRATION_APPROVED;
            }

            ApplicationRegistrationWorkflowDTO regWorkFlowDTO = (ApplicationRegistrationWorkflowDTO)workFlowDTO;
            Application application = ApplicationUtils.populateApplication(workFlowDTO.getWorkflowReference());
            dao.populateAppRegistrationWorkflowDTO(regWorkFlowDTO);

            Connection conn = null;
            try {
				conn = APIMgtDBUtil.getConnection();
				dao.updateApplicationRegistration(status,
	                    ((ApplicationRegistrationWorkflowDTO) workFlowDTO).getKeyType(),
	                    ((ApplicationRegistrationWorkflowDTO) workFlowDTO).getApplication().getId(), conn);
				APIMgtDBUtil.transactionCommit(conn);
			} catch (SQLException e) {
				log.error("Error while completing Application Registration entry.", e);
	            throw new WorkflowException("Error while completing Application Registration entry.", e);
			}finally{
				APIMgtDBUtil.closeConnection(conn);
			}
            
//            OauthAppRequest appInfoDTO = ApplicationCreator.createAppInfoDTO(null);
//            appInfoDTO.setMappingId(regWorkFlowDTO.getWorkflowReference());
//           // appInfoDTO.retrieveDTO();
//            if(application != null){
//                regWorkFlowDTO.setApplication(application);
//                regWorkFlowDTO.setAppInfoDTO(appInfoDTO);
//            }

            /*ApiMgtDAO dao = new ApiMgtDAO();

            ApplicationRegistrationWorkflowDTO regWorkFlowDTO = (ApplicationRegistrationWorkflowDTO)workFlowDTO;
            dao.populateAppRegistrationWorkflowDTO(regWorkFlowDTO);
            if(((ApplicationRegistrationWorkflowDTO) workFlowDTO).getApplication() != null){
                dao.updateApplicationRegistration(status,regWorkFlowDTO.getKeyType(),regWorkFlowDTO.getApplication().getId());
            }
            super.complete(workFlowDTO);
            if(((ApplicationRegistrationWorkflowDTO) workFlowDTO).getApplication() == null){
                throw new WorkflowException("Couldn't find application to complete the Registration process.");
            }
            */
        } catch (APIManagementException e) {
            log.error("Error while completing Application Registration entry.", e);
            throw new WorkflowException("Error while completing Application Registration entry.", e);
        }
    }

    /**
     * This method will create a oAuth client at oAuthServer.
     * and will create a mapping with APIM using consumerKey
     * @param workflowDTO
     * @throws APIManagementException
     */
    protected void generateKeysForApplication(ApplicationRegistrationWorkflowDTO workflowDTO, final Connection connection) throws
                                                                                              APIManagementException {
        ApiMgtDAO dao = new ApiMgtDAO();
        if (WorkflowStatus.APPROVED.equals(workflowDTO.getStatus())) {
            dogenerateKeysForApplication(workflowDTO, connection);

            if (workflowDTO.getApplicationInfo() != null && workflowDTO.getApplicationInfo().getClientId() != null) {
        		try {
        			dao.addAccessAllowDomains(workflowDTO.getApplicationInfo().getClientId(), workflowDTO.getAllowedDomains(), connection);
        		} catch (Exception ex) {
        			APIMgtDBUtil.transactionRollback(connection);
        			throw new APIManagementException(ex);
        		}
            }

        }
    }

    public static void dogenerateKeysForApplication(ApplicationRegistrationWorkflowDTO workflowDTO, final Connection connection) throws
                                                                                                    APIManagementException{
        log.debug("Registering Application and creating an Access Token... ");
        Application application = workflowDTO.getApplication();
        Subscriber subscriber = application.getSubscriber();
        ApiMgtDAO dao = new ApiMgtDAO();
        if (application == null || subscriber == null || workflowDTO.getAllowedDomains() == null) {
            dao.populateAppRegistrationWorkflowDTO(workflowDTO);
        }

        try {
            //get new key manager
            KeyManager keyManager = KeyManagerHolder.getKeyManagerInstance();

            StringBuilder applicationNameAfterAppend = new StringBuilder(application.getName());
            String keyType = workflowDTO.getKeyType();
            applicationNameAfterAppend.append("_").append(keyType);
            workflowDTO.getAppInfoDTO().getOAuthApplicationInfo()
                       .setClientName(applicationNameAfterAppend.toString());
            //createApplication on oAuthorization server.
            OAuthApplicationInfo oAuthApplication = keyManager.createApplication(workflowDTO.getAppInfoDTO());
            //Do application mapping with consumerKey.
            //dao.createApplicationRegistrationEntry(workflowDTO, true);

            //update associateApplication
            //application.updateAssociateOAuthApp(workflowDTO.getKeyType(), oAuthApplication);
            ApplicationUtils.updateOAuthAppAssociation(application,workflowDTO.getKeyType(),oAuthApplication, connection);

            //change create application status in to completed.
            dao.updateApplicationRegistration(APIConstants.AppRegistrationStatus.REGISTRATION_COMPLETED,
                    workflowDTO.getKeyType(),workflowDTO.getApplication().getId(), connection);

            workflowDTO.setApplicationInfo(oAuthApplication);

            /*keyMgtClient.getApplicationAccessKey(workflowDTO.getUserName(),workflowDTO.getApplication().getName(),
                                                 workflowDTO.getKeyType(),workflowDTO.getCallbackUrl(),
                                                 workflowDTO.getAllowedDomains(),
                                                 Long.toString(workflowDTO.getValidityTime()),
                                                 (String)oAuthApplication.getParameter("tokenScope"));
                                                 */


            AccessTokenRequest tokenRequest = ApplicationUtils.createAccessTokenRequest(oAuthApplication,null);
            AccessTokenInfo tokenInfo = keyManager.getNewApplicationAccessToken(tokenRequest);

            /*
            AccessTokenInfo info = TokenMgtDao.getAccessTokenForConsumerId(tokenRequest.getClientId());
            if (info == null) {
                TokenMgtDao.insertAccessTokenForConsumerKey(tokenRequest.getClientId(), tokenInfo);
            } else {
                TokenMgtDao.updateTokenForConsumerKey(tokenRequest.getClientId(), tokenInfo);
            }
            */
            workflowDTO.setAccessTokenInfo(tokenInfo);

        } catch (Exception e) {
            APIUtil.handleException("Error occurred while executing SubscriberKeyMgtClient.", e);
        }
    }

}
