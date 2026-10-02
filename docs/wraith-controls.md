# Wraith DCC controls

Open a connected Wraith DCC chat, then choose **⋮ → Wraith controls**.
The panel provides Help, My identity, Status, Bots, Bot tree and Relay to hub.
Responses remain in the same DCC window. Bots and Bot tree are hub commands;
Wraith applies its normal account permissions. Log in to the bot first.

Relay requires a hub bot name and confirmation. Other commands can be entered
in the DCC composer. The panel uses only the selected DCC session, never IRC
PRIVMSG or a channel; a disconnected session reports an error without reconnecting
or falling back to IRC. It does not store bot credentials or authenticate automatically.

Wraith command reference: https://github.com/wraith/wraith/blob/master/doc/help.txt

## FiSH private chat

Choose **Exchange FiSH key for bot PM** in the Wraith panel, or open the bot's
IRC private chat and select **Secure Chat → FiSH → Exchange FiSH key (DH1080)**.
This starts an explicit DH1080 exchange with the leaf bot over IRC NOTICE and
opens its private chat. On success the derived key is stored in the encrypted
key store and outgoing private messages use Wraith-compatible FiSH ECB. Existing
manually configured FiSH keys retain CBC sending. Incoming ECB and CBC remain supported.

This does not encrypt the DCC partyline, a relayed hub connection, or channels.
No remote commands are sent by the key-exchange action. Wait for the success
message before sending private messages that you expect to be encrypted.

Only a reply to a user-initiated exchange, from that peer on that network, can
install a key. Historical notices, unsolicited finishes, invalid public values,
and CBC negotiation are not accepted as an ECB exchange. Exchanges expire after
60 seconds and are cleared on IRC disconnect. Existing local keys stay unchanged
on timeout or rejection; the remote bot may already have changed its key, so retry
the exchange before continuing if it did not complete locally. Changing encryption
settings during an exchange cancels installation. +AGE must be disabled explicitly.

DH1080 and FiSH are legacy compatibility protocols: the exchange does not
authenticate identity and ECB does not provide message integrity. Verify the
shared key fingerprint through a trusted separate channel. This feature does
not automatically answer incoming DH1080_INIT requests or downgrade other chats.

Protocol reference: https://github.com/wraith/wraith/blob/master/src/crypto/dh_util.cc
