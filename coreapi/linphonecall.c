
/*
linphone
Copyright (C) 2010  Belledonne Communications SARL 
 (simon.morlat@linphone.org)

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
#ifdef WIN32
#include <time.h>
#endif
#include "linphonecore.h"
#include "sipsetup.h"
#include "lpconfig.h"
#include "private.h"




static MSList *make_codec_list(LinphoneCore *lc, const MSList *codecs, bool_t only_one_codec){
	MSList *l=NULL;
	const MSList *it;
	for(it=codecs;it!=NULL;it=it->next){
		PayloadType *pt=(PayloadType*)it->data;
		if ((pt->flags & PAYLOAD_TYPE_ENABLED) && linphone_core_check_payload_type_usability(lc,pt)){
			l=ms_list_append(l,payload_type_clone(pt));
			if (only_one_codec) break;
		}
	}
	return l;
}

static SalMediaDescription *create_local_media_description(LinphoneCore *lc, 
    		const char *localip, const char *username, bool_t only_one_codec){
	MSList *l;
	PayloadType *pt;
	SalMediaDescription *md=sal_media_description_new();
	md->nstreams=1;
	strncpy(md->addr,localip,sizeof(md->addr));
	strncpy(md->username,username,sizeof(md->username));
	md->bandwidth=linphone_core_get_download_bandwidth(lc);
	/*set audio capabilities */
	strncpy(md->streams[0].addr,localip,sizeof(md->streams[0].addr));
	md->streams[0].port=linphone_core_get_audio_port(lc);
	md->streams[0].proto=SalProtoRtpAvp;
	md->streams[0].type=SalAudio;
	md->streams[0].ptime=lc->net_conf.down_ptime;
	l=make_codec_list(lc,lc->codecs_conf.audio_codecs,only_one_codec);
	pt=payload_type_clone(rtp_profile_get_payload_from_mime(&av_profile,"telephone-event"));
	l=ms_list_append(l,pt);
	md->streams[0].payloads=l;
	
	if (lc->dw_audio_bw>0)
		md->streams[0].bandwidth=lc->dw_audio_bw;

	if (linphone_core_video_enabled (lc)){
		md->nstreams++;
		md->streams[1].port=linphone_core_get_video_port(lc);
		md->streams[1].proto=SalProtoRtpAvp;
		md->streams[1].type=SalVideo;
		l=make_codec_list(lc,lc->codecs_conf.video_codecs,only_one_codec);
		md->streams[1].payloads=l;
		if (lc->dw_video_bw)
			md->streams[1].bandwidth=lc->dw_video_bw;
	}
	return md;
}

static void linphone_call_init_common(LinphoneCall *call, LinphoneAddress *from, LinphoneAddress *to){
	call->refcnt=1;
	call->state=LinphoneCallInit;
	call->start_time=time(NULL);
	call->media_start_time=0;
	call->log=linphone_call_log_new(call, from, to);
	linphone_core_notify_all_friends(call->core,LINPHONE_STATUS_ONTHEPHONE);
	if (linphone_core_get_firewall_policy(call->core)==LINPHONE_POLICY_USE_STUN)
		linphone_core_run_stun_tests(call->core,call);
}

static void discover_mtu(LinphoneCore *lc, const char *remote){
	int mtu;
	if (lc->net_conf.mtu==0	){
		/*attempt to discover mtu*/
		mtu=ms_discover_mtu(remote);
		if (mtu>0){
			ms_set_mtu(mtu);
			ms_message("Discovered mtu is %i, RTP payload max size is %i",
				mtu, ms_get_payload_max_size());
		}
	}
}

LinphoneCall * linphone_call_new_outgoing(struct _LinphoneCore *lc, LinphoneAddress *from, LinphoneAddress *to)
{
	LinphoneCall *call=ms_new0(LinphoneCall,1);
	call->dir=LinphoneCallOutgoing;
	call->op=sal_op_new(lc->sal);
	sal_op_set_user_pointer(call->op,call);
	call->core=lc;
	linphone_core_get_local_ip(lc,linphone_address_get_domain(to),call->localip);
	call->localdesc=create_local_media_description (lc,call->localip,
		linphone_address_get_username(from),FALSE);
	linphone_call_init_common(call,from,to);
	discover_mtu(lc,linphone_address_get_domain (to));
	return call;
}

LinphoneCall * linphone_call_new_incoming(LinphoneCore *lc, LinphoneAddress *from, LinphoneAddress *to, SalOp *op){
	LinphoneCall *call=ms_new0(LinphoneCall,1);
	LinphoneAddress *me=linphone_core_get_primary_contact_parsed(lc);
	char *to_str;
	char *from_str;

	call->dir=LinphoneCallIncoming;
	sal_op_set_user_pointer(op,call);
	call->op=op;
	call->core=lc;

	if (lc->sip_conf.ping_with_options){
		/*the following sends an option request back to the caller so that
		 we get a chance to discover our nat'd address before answering.*/
		call->ping_op=sal_op_new(lc->sal);
		to_str=linphone_address_as_string(to);
		from_str=linphone_address_as_string(from);
		sal_op_set_route(call->ping_op,sal_op_get_network_origin(call->op));
		sal_op_set_user_pointer(call->ping_op,call);
		sal_ping(call->ping_op,to_str,from_str);
		ms_free(to_str);
		ms_free(from_str);
	}
	
	linphone_address_clean(from);
	linphone_core_get_local_ip(lc,linphone_address_get_domain(from),call->localip);
	call->localdesc=create_local_media_description (lc,call->localip,
	    linphone_address_get_username(me),lc->sip_conf.only_one_codec);
	linphone_call_init_common(call, from, to);
	discover_mtu(lc,linphone_address_get_domain(from));
	linphone_address_destroy(me);
	return call;
}

/* this function is called internally to get rid of a call.
 It performs the following tasks:
 - remove the call from the internal list of calls
 - unref the LinphoneCall object
 - update the call logs accordingly
*/

void linphone_call_set_terminated(LinphoneCall *call){
	LinphoneCallStatus status=LinphoneCallAborted;
	LinphoneCore *lc=call->core;

	if (ms_list_size(lc->calls)==0)
		linphone_core_notify_all_friends(lc,lc->prev_mode);
	
	linphone_core_update_allocated_audio_bandwidth(lc);
	if (call->state==LinphoneCallAVRunning){
		status=LinphoneCallSuccess;
		
	}
	linphone_call_log_completed(call->log,call, status);
	call->state=LinphoneCallTerminated;
	if (linphone_core_del_call(lc,call) != 0){
		ms_error("could not remove the call from the list !!!");
	}
	if (call == linphone_core_get_current_call(lc)){
		ms_message("destroying the current call\n");
		linphone_core_unset_the_current_call(lc);
	}
	if (call->op!=NULL) {
		/* so that we cannot have anymore upcalls for SAL
		 concerning this call*/
		sal_op_release(call->op);
		call->op=NULL;
	}
	linphone_call_unref(call);
}

static void linphone_call_destroy(LinphoneCall *obj)
{
	if (obj->op!=NULL) {
		sal_op_release(obj->op);
		obj->op=NULL;
	}
	if (obj->resultdesc!=NULL) {
		sal_media_description_unref(obj->resultdesc);
		obj->resultdesc=NULL;
	}
	if (obj->localdesc!=NULL) {
		sal_media_description_unref(obj->localdesc);
		obj->localdesc=NULL;
	}
	if (obj->ping_op) {
		sal_op_release(obj->ping_op);
	}
	ms_free(obj);
}

/**
 * @addtogroup calls
 * @{
**/

/**
 * Increments the call 's reference count.
 * An application that wishes to retain a pointer to call object
 * must use this function to unsure the pointer remains
 * valid. Once the application no more needs this pointer,
 * it must call linphone_call_unref().
**/
void linphone_call_ref(LinphoneCall *obj){
	obj->refcnt++;
}

/**
 * Decrements the call object reference count.
 * See linphone_call_ref().
**/
void linphone_call_unref(LinphoneCall *obj){
	obj->refcnt--;
	if (obj->refcnt==0)
		linphone_call_destroy(obj);
}

/**
 * Returns true if the call is paused.
**/
bool_t linphone_call_paused(LinphoneCall *call){
	return call->state==LinphoneCallPaused;
}

/**
 * Returns the remote address associated to this call
 *
**/
const LinphoneAddress * linphone_call_get_remote_address(const LinphoneCall *call){
	return call->dir==LinphoneCallIncoming ? call->log->from : call->log->to;
}

/**
 * Returns the remote address associated to this call as a string.
 *
 * The result string must be freed by user using ms_free().
**/
char *linphone_call_get_remote_address_as_string(const LinphoneCall *call){
	return linphone_address_as_string(linphone_call_get_remote_address(call));
}

/**
 * Retrieves the call's current state.
**/
LinphoneCallState linphone_call_get_state(const LinphoneCall *call){
	return call->state;
}

/**
 * Get the user_pointer in the LinphoneCall
 *
 * @ingroup call_control
 *
 * return user_pointer an opaque user pointer that can be retrieved at any time
**/
void *linphone_call_get_user_pointer(LinphoneCall *call)
{
	return call->user_pointer;
}

/**
 * Set the user_pointer in the LinphoneCall
 *
 * @ingroup call_control
 *
 * the user_pointer is an opaque user pointer that can be retrieved at any time in the LinphoneCall
**/
void linphone_call_set_user_pointer(LinphoneCall *call, void *user_pointer)
{
	call->user_pointer = user_pointer;
}

/**
 * @}
**/

