package com.vitorpamplona.amethyst.service

import com.vitorpamplona.amethyst.model.decodePublicKey
import com.vitorpamplona.amethyst.service.relays.FeedType
import com.vitorpamplona.amethyst.service.relays.TypedFilter
import nostr.postr.JsonFilter
import nostr.postr.bechToBytes
import nostr.postr.events.MetadataEvent
import nostr.postr.toHex

object NostrSearchEventOrUserDataSource: NostrDataSource("SingleEventFeed") {
  private var hexToWatch: String? = null

  private fun createAnythingWithIDFilter(): List<TypedFilter>? {
    if (hexToWatch == null) {
      return null
    }

    // downloads all the reactions to a given event.
    return listOf(
      TypedFilter(
        types = FeedType.values().toSet(),
        filter = JsonFilter(
          ids = listOfNotNull(hexToWatch)
        )
      ),
      TypedFilter(
        types = FeedType.values().toSet(),
        filter = JsonFilter(
          kinds = listOf(MetadataEvent.kind),
          authors = listOfNotNull(hexToWatch)
        )
      )
    )
  }

  val searchChannel = requestNewChannel()

  override fun updateChannelFilters() {
    searchChannel.typedFilters = createAnythingWithIDFilter()
  }

  fun search(eventId: String) {
    try {
      val hex = if (eventId.startsWith("npub") || eventId.startsWith("nsec")) {
        decodePublicKey(eventId).toHex()
      } else if (eventId.startsWith("note")) {
        eventId.bechToBytes().toHex()
      } else {
        eventId
      }
      hexToWatch = hex
      sendSearchRequest(hexToWatch ?: throw IllegalArgumentException("hex is null"))
    } catch (e: Exception) {
      println(e)
    }
  }

  fun clear() {
    hexToWatch = null
  }
}