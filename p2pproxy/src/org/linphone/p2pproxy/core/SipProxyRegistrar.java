/*
p2pproxy Copyright (C) 2007  Jehan Monnier ()

SipListener.java - sip proxy.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
*/

package org.linphone.p2pproxy.core;


import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import net.jxta.document.Advertisement;
import net.jxta.endpoint.MessageElement;
import net.jxta.endpoint.StringMessageElement;
import net.jxta.pipe.OutputPipe;
import net.jxta.pipe.PipeMsgEvent;
import net.jxta.pipe.PipeMsgListener;
import net.jxta.protocol.PipeAdvertisement;

import org.apache.log4j.Logger;
import org.apache.log4j.NDC;
import org.linphone.p2pproxy.api.P2pProxyException;
import org.linphone.p2pproxy.api.P2pProxyRtpRelayManagement;
import org.linphone.p2pproxy.api.P2pProxyUserNotFoundException;

import org.linphone.p2pproxy.core.media.rtprelay.MediaType;
import org.linphone.p2pproxy.core.media.rtprelay.SdpProcessorImpl;
import org.zoolu.sip.address.NameAddress;
import org.zoolu.sip.address.SipURL;
import org.zoolu.sip.header.ExpiresHeader;
import org.zoolu.sip.header.Header;
import org.zoolu.sip.header.MultipleHeader;
import org.zoolu.sip.header.RecordRouteHeader;
import org.zoolu.sip.header.RouteHeader;
import org.zoolu.sip.header.ViaHeader;
import org.zoolu.sip.message.Message;
import org.zoolu.sip.message.MessageFactory;
import org.zoolu.sip.provider.SipProvider;
import org.zoolu.sip.provider.SipProviderListener;
import org.zoolu.sip.provider.SipStack;
import org.zoolu.sip.transaction.TransactionServer;
public class SipProxyRegistrar implements SipProviderListener,PipeMsgListener,SipProxyRegistrarMBean {
   private final static Logger mLog = Logger.getLogger(SipProxyRegistrar.class);   
   public final static String REGISTRAR_PORT="org.linphone.p2pproxy.SipListener.registrar.port";
   public final static String UDP_PORT_BEGING="org.linphone.p2pproxy.udp.port.begin";
   public final static String UDP_MEDIA_RELAY_PORT_START="org.linphone.p2pproxy.udp-media-relay.port.start";

   //
   private final SipProvider mProvider;
   private final JxtaNetworkManager mJxtaNetworkManager;
   private final ExecutorService mPool;

   private final Map<String,Registration> mRegistrationTab = new HashMap<String,Registration>(); 
   private final Map<String,SipMessageTask> mCancalableTaskTab = new HashMap<String,SipMessageTask>(); 

   private final P2pProxyAccountManagementMBean mP2pProxyAccountManagement;
   private final Configurator mProperties;
   private final SdpProcessor mSdpProcessor;
   //private long mNumberOfEstablishedCall;
   private long mNumberOfRefusedRegistration;
   private long mNumberOfSuccessfullRegistration;
   private long mNumberOfUSerNotFound;
   private long mNumberOfUnknownUSers;
   private long mNumberOfUnknownUsersForRegistration;
   private long mNumberOfUnRegistration;
   
   public static class Registration {
      long RegistrationDate;
      long Expiration;
      public NetworkResources NetResources;
      public  Map<MediaType,InetSocketAddress> RtpRelays = new HashMap<MediaType,InetSocketAddress>() ;
      String Contact;
      final String From;
      public Registration(String aFrom) {From = aFrom;}
   }
   
   class SipMessageTask implements Callable<Boolean> {
      private final SipProvider mProvider;
      private final Message mMessage;
      private Future<?> mFuture;
      
      /**
       * @return Returns the mMessage.
       */
      public Message getMessage() {
         return mMessage;
      }
      SipMessageTask(SipProvider aProvider, Message aMessage) {
         mProvider = aProvider;
         mMessage = aMessage;
      }
      public Boolean call() throws Exception {
    	  NDC.push(mMessage.getFirstLine() + mMessage.getCallIdHeader().getCallId() +":");
         try {
            if (mMessage.isRequest()) {
               if (mMessage.isRegister()) {
                  processRegister(mProvider, mMessage);
               } else {
                  proxyRequest(mProvider, mMessage);
               }
            } else {
               proxyResponse(mProvider, mMessage);
            }
            synchronized (SipProxyRegistrar.this) {
               if (mMessage.isInvite() && mCancalableTaskTab.containsKey(mMessage.getCallIdHeader().getCallId()) )  {
                  mCancalableTaskTab.remove(mMessage.getCallIdHeader().getCallId());
               }
            }
         } catch (InterruptedException eInter) {
            mLog.info("request interrupted",eInter);
            //nop
         }
         catch (Exception e) {
            mLog.error("unexpected behavior",e);
            if (mMessage.isRequest()) {
               Message lResp= null;
               lResp = MessageFactory.createResponse(mMessage,500,e.getMessage(),null);
               TransactionServer lTransactionServer = new TransactionServer(mProvider,mMessage,null);
               lTransactionServer.respondWith(lResp);
               synchronized (SipProxyRegistrar.this) {
                  if (mMessage.isInvite() && mCancalableTaskTab.containsKey(mMessage.getCallIdHeader().getCallId()) )  {
                     mCancalableTaskTab.remove(mMessage.getCallIdHeader().getCallId());
                  }
               }
            }
         } finally {
        	 NDC.pop();
         }
         return true;
      }
      /**
       * @return Returns the mFuture.
       */
      public Future<?> getFuture() {
         return mFuture;
      }
      /**
       * @param future The mFuture to set.
       */
      public void setFuture(Future<?> future) {
         mFuture = future;
      }
      
   }
   
   public SipProxyRegistrar(Configurator lProperties,JxtaNetworkManager aJxtaNetworkManager,P2pProxyAccountManagementMBean aP2pProxyAccountManagement,P2pProxyRtpRelayManagement aP2pProxyRtpRelayManagement) {
      mJxtaNetworkManager =  aJxtaNetworkManager;
      mP2pProxyAccountManagement = aP2pProxyAccountManagement;
      mProperties = lProperties;
      File lFile = new File(SipStack.log_path);
      if (lFile.exists() == false) lFile.mkdir();
      int lPort = Integer.parseInt(lProperties.getProperty(REGISTRAR_PORT, "5060"));
      String[] lProto = {SipProvider.PROTO_UDP};
      mProvider=new SipProvider(null,lPort,lProto,SipProvider.ALL_INTERFACES);
      mProvider.addSipProviderListener(SipProvider.PROMISQUE,this);
      mPool = Executors.newCachedThreadPool();
      mSdpProcessor = new SdpProcessorImpl(aP2pProxyRtpRelayManagement);
      
   }
   public synchronized void onReceivedMessage(SipProvider aProvider, Message aMessage) {
      String lCallId = aMessage.getCallIdHeader().getCallId();
      if (mLog.isInfoEnabled()) mLog.info("receiving message ["+aMessage+"]");
      if (aMessage.isCancel() && mCancalableTaskTab.containsKey(lCallId) ) {
         // search for pending transaction
         SipMessageTask lPendingSipMessageTask = mCancalableTaskTab.get(lCallId);
         lPendingSipMessageTask.getFuture().cancel(true);
         mCancalableTaskTab.remove(lCallId);

         removeVia(mProvider,lPendingSipMessageTask.getMessage());
         // accept cancel
         Message lCancelResp = MessageFactory.createResponse(aMessage,200,"ok",null);
         TransactionServer lCancelTransactionServer = new TransactionServer(mProvider,aMessage,null);
         lCancelTransactionServer.respondWith(lCancelResp);
         
         // cancel invite
         Message lInviteResp = MessageFactory.createResponse(lPendingSipMessageTask.getMessage(),487,"Request Terminated",null);
         TransactionServer lInviteTransactionServer = new TransactionServer(mProvider,lPendingSipMessageTask.getMessage(),null);
         lInviteTransactionServer.respondWith(lInviteResp);          
      } else {
         // normal behavior
         SipMessageTask lSipMessageTask = new SipMessageTask(aProvider,aMessage);
         lSipMessageTask.setFuture(mPool.submit(lSipMessageTask));
         if (aMessage.isInvite()) {
            mCancalableTaskTab.put(aMessage.getCallIdHeader().getCallId(),lSipMessageTask);                
         }
         
      }
      
   }
//////////////////////////////////////////////////////////////////////
////Proxy methods
/////////////////////////////////////////////////////////////////////	
   private void proxyResponse(SipProvider aProvider, Message aMessage) throws NumberFormatException, InterruptedException, P2pProxyException, IOException {
      //1 remove via header   
      removeVia(aProvider,aMessage);
      String lFrom =  aMessage.getFromHeader().getNameAddress().getAddress().toString();
      mSdpProcessor.processSdpBeforeSendingToPipe(aMessage);
      OutputPipe lOutputPipe = sendMessageToPipe(lFrom,aMessage.toString());
      mSdpProcessor.processSdpAfterSentToPipe( aMessage,lOutputPipe);
   }
   private void proxyRequest(SipProvider aProvider, Message aMessage) throws Exception {

	   if (aMessage.isAck() && aMessage.getToHeader().getTag() == null) {
		   // just terminate the Invite transaction
		   return;
	   }

	   if (aMessage.isInvite() == true) {
		   // 100 trying
		   TransactionServer lTransactionServer = new TransactionServer(aProvider,aMessage,null);
		   Message l100Trying = MessageFactory.createResponse(aMessage,100,"trying",null);
		   lTransactionServer.respondWith(l100Trying);
	   }

	   String lTo =  aMessage.getToHeader().getNameAddress().getAddress().toString();
	   //remove route
	   MultipleHeader lMultipleRoute = aMessage.getRoutes();
	   if (lMultipleRoute != null) {
		   lMultipleRoute.removeTop();
		   aMessage.setRoutes(lMultipleRoute);
	   }
	   // add Via only udp
	   addVia(aProvider,aMessage);
	   // add recordRoute
	   addRecordRoute(aProvider,aMessage);
	   try {
	      mSdpProcessor.processSdpBeforeSendingToPipe(aMessage);
	         // proxy message to pipe
		   OutputPipe lOutputPipe = sendMessageToPipe(lTo,aMessage.toString());
		   mSdpProcessor.processSdpAfterSentToPipe( aMessage,lOutputPipe);
	   } catch (P2pProxyUserNotFoundException e) {
		   //remove via 
		   removeVia(aProvider, aMessage);
		   if (aMessage.isInvite()) {
			   Message lresp = MessageFactory.createResponse(aMessage,404,e.getMessage(),null);
			   TransactionServer lTransactionServer = new TransactionServer(aProvider,aMessage,null);
			   lTransactionServer.respondWith(lresp);
		   } else {
			   throw e;
		   }
	   } catch (Exception e2) {
		   //remove via 
		   removeVia(aProvider, aMessage);
		   throw e2;

	   }
   }
   
   
//////////////////////////////////////////////////////////////////////
////Registrar methods
/////////////////////////////////////////////////////////////////////	
   
   private synchronized void processRegister(SipProvider aProvider, Message aMessage) throws IOException, P2pProxyException {
      
      TransactionServer lTransactionServer = new TransactionServer(aProvider,aMessage,null);
      Message l100Trying = MessageFactory.createResponse(aMessage,100,"trying",null);
      lTransactionServer.respondWith(l100Trying);
      Registration lRegistration=null;
      
      //check if already registered
      
      String lFromName = aMessage.getFromHeader().getNameAddress().getAddress().toString();
      if (mRegistrationTab.containsKey(lFromName)) {
         
         updateRegistration(lRegistration = mRegistrationTab.get(lFromName),aMessage);
         
         if (aMessage.getExpiresHeader().getDeltaSeconds() == 0) {
            mRegistrationTab.remove(lFromName);
         } 
         
      } else {
         // new registration
         // test if account already created
         
         if (mP2pProxyAccountManagement.isValidAccount(lFromName)) {
         lRegistration = new Registration(lFromName);
         lRegistration.Contact = aMessage.getContactHeader().getNameAddress().getAddress().toString();;
         updateRegistration(lRegistration,aMessage);
         mRegistrationTab.put(lFromName, lRegistration);
         } else {
            // create negative answers
            mLog.info("account for user ["+lFromName+"not crteated yet");
            Message lresp = MessageFactory.createResponse(aMessage,404,"Not found",null);
            lTransactionServer.respondWith(lresp);
            return;
         }
      }
      // ok, create answers
      Message lresp = MessageFactory.createResponse(aMessage,200,"Ok",null);
      lresp.addContactHeader(aMessage.getContactHeader(), false);
      ExpiresHeader lExpireHeader = new  ExpiresHeader((int) (lRegistration.Expiration/1000));
      lresp.addHeader(lExpireHeader, false);
      lTransactionServer.respondWith(lresp);
      
   }
   private void updateRegistration(Registration aRegistration, Message aRegistrationMessage) throws IOException {
      aRegistration.RegistrationDate = System.currentTimeMillis();
      // default registration periode
      aRegistration.Expiration = 3600000;
      if (aRegistrationMessage.getExpiresHeader() != null ) {
         aRegistration.Expiration =  aRegistrationMessage.getExpiresHeader().getDeltaSeconds()*1000; 
      }
      
      if (aRegistration.NetResources == null) {
         // new registration, create pipe
         aRegistration.NetResources = new NetworkResources(aRegistration.From,mJxtaNetworkManager);
         aRegistration.NetResources.addPipeMsgListener(this);
      }
      
      aRegistration.NetResources.publish(aRegistration.Expiration);
   }
   
//////////////////////////////////////////////////////////////////////
////jxta service methods
/////////////////////////////////////////////////////////////////////	
   
 
   public void pipeMsgEvent(PipeMsgEvent anEvent) {
	  MessageElement lMessageElement = anEvent.getMessage().getMessageElement("SIP");
	  if (lMessageElement == null) {
		  //nop, this is not for me
		  return;
	  }
	  String lMesssage = lMessageElement.toString();
      mLog.info("pipe event sip message["+lMesssage+"]");
      Message lSipMessage = new Message(lMesssage);
      // process request
      if (lSipMessage.isRequest()) {
         SipURL  lNextHope ;
         // get next hope from registrar
         String lToName = lSipMessage.getToHeader().getNameAddress().getAddress().toString();
         if (mRegistrationTab.containsKey(lToName)) {
            lNextHope = new SipURL(mRegistrationTab.get(lToName).Contact); 
         } else {
            mLog.error("user ["+lToName+"] not found");
            return;
         }
         //RequestLine lRequestLine = new RequestLine(lSipMessage.getRequestLine().getMethod(),lNextHope);
         //lSipMessage.setRequestLine(lRequestLine);
         MultipleHeader lMultipleRoute = lSipMessage.getRoutes();
         RouteHeader lRouteHeader = new RouteHeader(new NameAddress(lNextHope+";lr"));
         //lRouteHeader.setParameter("lr", null);
         if (lMultipleRoute != null) {
            lMultipleRoute.addTop(lRouteHeader);
            lSipMessage.setRoutes(lMultipleRoute);
         } else {
            lSipMessage.addRouteHeader(lRouteHeader);
         }
         // add Via only udp
         addVia(mProvider,lSipMessage);
         // add recordRoute
         addRecordRoute(mProvider,lSipMessage);
         
      } else {
         //response
         //1 remove via header   
         removeVia(mProvider,lSipMessage);
      }
      try {
         mSdpProcessor.processSdpBeforeSendingToSipUA( lSipMessage);
	} catch (P2pProxyException e) {
		mLog.error("enable to re-write sdp",e);
	}
      
      mProvider.sendMessage(lSipMessage);
      //
   }
   
   private Advertisement getPipeAdv(String aUser,long aDiscoveryTimout,boolean isTryFromLocal) throws InterruptedException, P2pProxyUserNotFoundException, IOException {
      // search on all peers
      try {
         return mJxtaNetworkManager.getAdvertisement(null,aUser, isTryFromLocal);
      } catch (P2pProxyAdvertisementNotFoundException e) {
         throw new P2pProxyUserNotFoundException(e);
      }	
   }
   private OutputPipe sendMessageToPipe(String aDestination,String lContent) throws NumberFormatException, InterruptedException, P2pProxyException, IOException {
      
      //1 search for pipe
      long lTimeout = JxtaNetworkManager.ADV_DISCOVERY_TIMEOUT_INT;
      PipeAdvertisement lPipeAdvertisement = (PipeAdvertisement)getPipeAdv(aDestination,lTimeout,true);
      OutputPipe lOutputPipe=null;
      try {
         // create output pipe
         lOutputPipe = mJxtaNetworkManager.getPeerGroup().getPipeService().createOutputPipe(lPipeAdvertisement, lTimeout);
         //create the message
      } catch (IOException e) {
         //second try from remote only to avoid wrong cached value
    	  mJxtaNetworkManager.getPeerGroup().getDiscoveryService().flushAdvertisement(lPipeAdvertisement);
    	  mLog.warn("cannot create output pipe, trying to ask from rdv ",e);
         lPipeAdvertisement = (PipeAdvertisement)getPipeAdv(aDestination,lTimeout,false);
         lOutputPipe = mJxtaNetworkManager.getPeerGroup().getPipeService().createOutputPipe(lPipeAdvertisement, lTimeout);
      }
      net.jxta.endpoint.Message lMessage = new net.jxta.endpoint.Message();
      StringMessageElement lStringMessageElement = new StringMessageElement("SIP", lContent, null);
      lMessage.addMessageElement("SIP", lStringMessageElement);
      //send the message
      lOutputPipe.send(lMessage);
      mLog.debug("message sent to ["+aDestination+"]");
      return lOutputPipe;
      
   }
   private void addVia(SipProvider aProvider, Message aMessage) {
      ViaHeader via=new ViaHeader("udp",aProvider.getViaAddress(),aProvider.getPort());
      String branch=aProvider.pickBranch(aMessage);
      via.setBranch(branch);
      aMessage.addViaHeader(via);      
   }
   private void addRecordRoute(SipProvider aProvider, Message aMessage) {
      SipURL lRecordRoute;
      lRecordRoute=new SipURL(aProvider.getViaAddress(),aProvider.getPort());
      lRecordRoute.addLr();
      RecordRouteHeader lRecordRouteHeader=new RecordRouteHeader(new NameAddress(lRecordRoute));
      aMessage.addRecordRouteHeader(lRecordRouteHeader);    
   }
   private void removeVia(SipProvider aProvider, Message aMessage) {
	   synchronized (aMessage) {
		   ViaHeader lViaHeader =new ViaHeader((Header)aMessage.getVias().getHeaders().elementAt(0));
		   if (lViaHeader.getHost().equals(aProvider.getViaAddress()) && lViaHeader.getPort() == aProvider.getPort() ) {
			   aMessage.removeViaHeader();
		   }       
	   }
   }
 //   public long getNumberOfEstablishedCall() {
//      return mNumberOfEstablishedCall;
//   }
   public long getNumberOfRefusedRegistration() {
      return mNumberOfRefusedRegistration;
   }
   public long getNumberOfSuccessfullRegistration() {
      return mNumberOfSuccessfullRegistration;
   }
   public long getNumberOfUSerNotFound() {
      return mNumberOfUSerNotFound;
   }
   public long getNumberOfUnknownUSers() {
      return mNumberOfUnknownUSers;
   }
   public long getNumberOfUnknownUsersForRegistration() {
       return mNumberOfUnknownUsersForRegistration;
   }
   public String[] getRegisteredList() {
      String[] lRegisteredList = new String[mRegistrationTab.size()] ;
      int i=0;
      for (String lRegistrationKey : mRegistrationTab.keySet()) {
         lRegisteredList[i++] = lRegistrationKey;
      }
      return   lRegisteredList;
   }
   public long getNumberOfUnRegistration() {
      return mNumberOfUnRegistration;
   }
}
