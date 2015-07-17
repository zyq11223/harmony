package edu.snu.reef.em.msg;

import edu.snu.reef.em.avro.AvroElasticMemoryMessage;
import edu.snu.reef.em.utils.AvroUtils;
import org.apache.reef.wake.remote.Codec;

import javax.inject.Inject;

/**
 * Codec for AvroElasticMemoryMessages.
 * Simply uses AvroUtils to encode and decode messages.
 */
public final class ElasticMemoryMsgCodec
    implements Codec<AvroElasticMemoryMessage> {

  @Inject
  private ElasticMemoryMsgCodec() {
  }

  @Override
  public byte[] encode(final AvroElasticMemoryMessage msg) {
    return AvroUtils.toBytes(msg, AvroElasticMemoryMessage.class);
  }

  @Override
  public AvroElasticMemoryMessage decode(final byte[] data) {
    return AvroUtils.fromBytes(data, AvroElasticMemoryMessage.class);
  }
}
