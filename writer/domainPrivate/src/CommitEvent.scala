package app.writer.domain.internal

import java.time.Instant
import zio.json.*

case class CommitEvent(committedAt: Instant) derives JsonCodec
