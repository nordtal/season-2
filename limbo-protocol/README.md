# limbo-protocol

The wire protocol between the proxy and limbo: why a player is waiting (`WaitReason`) and how it
travels over the plugin channel (`LimboProtocol`). Both ends depend on this module and on nothing
else of each other.
