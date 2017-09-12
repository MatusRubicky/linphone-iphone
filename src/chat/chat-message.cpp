/*
 * chat-message.cpp
 * Copyright (C) 2017  Belledonne Communications SARL
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

#include <unordered_map>

#include "db/events-db.h"
#include "object/object-p.h"

#include "chat-message.h"

// =============================================================================

LINPHONE_BEGIN_NAMESPACE

using namespace std;

class ChatMessagePrivate : public ObjectPrivate {
private:
	weak_ptr<ChatRoom> chatRoom;
	ChatMessage::Direction direction = ChatMessage::Incoming;
	// LinphoneAddress *from;
	// LinphoneAddress *to;
	shared_ptr<ErrorInfo> errorInfo;
	string contentType;
	string text;
	bool isSecured = false;
	bool isReadOnly = false;
	time_t time = 0;
	string id;
	string appData;
	list<shared_ptr<Content> > contents;
	shared_ptr<Content> private_content;
	unordered_map<string, string> customHeaders;
	ChatMessage::State state = ChatMessage::Idle;
	shared_ptr<EventsDb> eventsDb;

	L_DECLARE_PUBLIC(ChatMessage);
};

// -----------------------------------------------------------------------------

ChatMessage::ChatMessage (ChatMessagePrivate &p) : Object(p) {}

shared_ptr<ChatRoom> ChatMessage::getChatRoom () const {
	L_D(const ChatMessage);
	shared_ptr<ChatRoom> chatRoom = d->chatRoom.lock();
	if (!chatRoom) {
		// TODO.
	}
	return chatRoom;
}

ChatMessage::Direction ChatMessage::getDirection () const {
	L_D(const ChatMessage);
	return d->direction;
}

shared_ptr<const Address> ChatMessage::getFromAddress () const {
	// TODO.
	return nullptr;
}

shared_ptr<const Address> ChatMessage::getToAddress () const {
	// TODO.
	return nullptr;
}

shared_ptr<const Address> ChatMessage::getLocalAddress () const {
	// TODO.
	return nullptr;
}

shared_ptr<const Address> ChatMessage::getRemoteAddress () const {
	// TODO.
	return nullptr;
}

ChatMessage::State ChatMessage::getState () const {
	L_D(const ChatMessage);
	return d->state;
}

shared_ptr<const ErrorInfo> ChatMessage::getErrorInfo () const {
	L_D(const ChatMessage);
	return d->errorInfo;
}

string ChatMessage::getContentType () const {
	L_D(const ChatMessage);
	return d->contentType;
}

string ChatMessage::getText () const {
	L_D(const ChatMessage);
	return d->text;
}

void ChatMessage::setText (const string &text) {
	L_D(ChatMessage);
	d->text = text;
}

void ChatMessage::send () const {
	// TODO.
}

bool ChatMessage::containsReadableText () const {
	// TODO: Check content type.
	return true;
}

bool ChatMessage::isSecured () const {
	L_D(const ChatMessage);
	return d->isSecured;
}

bool ChatMessage::isReadOnly () const {
	L_D(const ChatMessage);
	return d->isReadOnly;
}

time_t ChatMessage::getTime () const {
	L_D(const ChatMessage);
	return d->time;
}

string ChatMessage::getId () const {
	L_D(const ChatMessage);
	return d->id;
}

string ChatMessage::getAppdata () const {
	L_D(const ChatMessage);
	return d->appData;
}

void ChatMessage::setAppdata (const string &appData) {
	L_D(ChatMessage);
	d->appData = appData;
}

list<shared_ptr<const Content> > ChatMessage::getContents () const {
	L_D(const ChatMessage);
	list<shared_ptr<const Content> > contents;
	for (const auto &content : d->contents)
		contents.push_back(content);
	return contents;
}

void ChatMessage::addContent (const shared_ptr<Content> &content) {
	L_D(ChatMessage);
	d->contents.push_back(content);
}

void ChatMessage::removeContent (const shared_ptr<const Content> &content) {
	L_D(ChatMessage);
	d->contents.remove(const_pointer_cast<Content>(content));
}

string ChatMessage::getCustomHeaderValue (const string &headerName) const {
	L_D(const ChatMessage);
	try {
		return d->customHeaders.at(headerName);
	} catch (const exception &) {
		// Key doesn't exist.
	}
	return "";
}

void ChatMessage::addCustomHeader (const string &headerName, const string &headerValue) {
	L_D(ChatMessage);
	d->customHeaders[headerName] = headerValue;
}

void ChatMessage::removeCustomHeader (const string &headerName) {
	L_D(ChatMessage);
	d->customHeaders.erase(headerName);
}

LINPHONE_END_NAMESPACE
