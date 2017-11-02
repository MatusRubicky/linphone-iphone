/*
 * chat-room-p.h
 * Copyright (C) 2010-2017 Belledonne Communications SARL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 */

#ifndef _CHAT_ROOM_P_H_
#define _CHAT_ROOM_P_H_

#include <unordered_set>

#include "chat/notification/is-composing.h"
#include "chat-room.h"
#include "object/object-p.h"

// =============================================================================

LINPHONE_BEGIN_NAMESPACE

class ChatRoomPrivate : public ObjectPrivate, public IsComposingListener {
	friend class ChatMessagePrivate;

public:
	ChatRoomPrivate () = default;

private:
	static int createChatMessageFromDb (void *data, int argc, char **argv, char **colName);

public:
	void addTransientMessage (const std::shared_ptr<ChatMessage> &msg);
	void addWeakMessage (const std::shared_ptr<ChatMessage> &msg);
	std::list<std::shared_ptr<ChatMessage>> getTransientMessages () const {
		return transientMessages;
	}

	void moveTransientMessageToWeakMessages (const std::shared_ptr<ChatMessage> &msg);
	void removeTransientMessage (const std::shared_ptr<ChatMessage> &msg);

	void release ();
	void sendImdn (const std::string &content, LinphoneReason reason);

	void setState (ChatRoom::State newState);

	virtual void sendMessage (const std::shared_ptr<ChatMessage> &msg);

protected:
	void sendIsComposingNotification ();

	int createChatMessageFromDb (int argc, char **argv, char **colName);
	std::shared_ptr<ChatMessage> getTransientMessage (unsigned int storageId) const;
	std::shared_ptr<ChatMessage> getWeakMessage (unsigned int storageId) const;
	std::list<std::shared_ptr<ChatMessage>> findMessages (const std::string &messageId);
	virtual void storeOrUpdateMessage (const std::shared_ptr<ChatMessage> &msg);

public:
	virtual LinphoneReason messageReceived (SalOp *op, const SalMessage *msg);
	void realtimeTextReceived (uint32_t character, LinphoneCall *call);

protected:
	void chatMessageReceived (const std::shared_ptr<ChatMessage> &msg);
	void imdnReceived (const std::string &text);
	void isComposingReceived (const Address &remoteAddr, const std::string &text);

private:
	void notifyChatMessageReceived (const std::shared_ptr<ChatMessage> &msg);
	void notifyIsComposingReceived (const Address &remoteAddr, bool isComposing);
	void notifyStateChanged ();
	void notifyUndecryptableMessageReceived (const std::shared_ptr<ChatMessage> &msg);

private:
	/* IsComposingListener */
	void onIsComposingStateChanged (bool isComposing) override;
	void onIsRemoteComposingStateChanged (const Address &remoteAddr, bool isComposing) override;
	void onIsComposingRefreshNeeded () override;

public:
	LinphoneCall *call = nullptr;
	ChatRoom::State state = ChatRoom::State::None;
	Address peerAddress;
	bool isComposing = false;
	std::unordered_set<std::string> remoteIsComposing;
	std::list<std::shared_ptr<ChatMessage>> transientMessages;

	std::list<std::weak_ptr<ChatMessage>> weakMessages;

	// TODO: Remove me. Must be present only in rtt chat room.
	std::shared_ptr<ChatMessage> pendingMessage;

	// TODO: Use CoreAccessor on IsComposing. And avoid pointer if possible.
	std::unique_ptr<IsComposing> isComposingHandler;

private:
	L_DECLARE_PUBLIC(ChatRoom);
};

LINPHONE_END_NAMESPACE

#endif // ifndef _CHAT_ROOM_P_H_
