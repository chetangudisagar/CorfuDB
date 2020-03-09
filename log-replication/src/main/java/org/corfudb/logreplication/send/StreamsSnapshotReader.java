package org.corfudb.logreplication.send;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.corfudb.logreplication.message.LogReplicationEntry;
import org.corfudb.logreplication.message.MessageType;
import org.corfudb.logreplication.fsm.LogReplicationConfig;
import org.corfudb.logreplication.message.DataMessage;
import org.corfudb.protocols.logprotocol.OpaqueEntry;
import org.corfudb.protocols.logprotocol.SMREntry;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.exceptions.TrimmedException;
import org.corfudb.runtime.view.Address;
import org.corfudb.runtime.view.StreamOptions;
import org.corfudb.runtime.view.stream.OpaqueStream;

import javax.annotation.concurrent.NotThreadSafe;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;


@Slf4j
@NotThreadSafe
/**
 *  Default snapshot reader implementation
 *
 *  This implementation provides reads at the stream level (no coalesced state).
 *  It generates TxMessages which will be transmitted by the DataSender (provided by the application).
 */
public class StreamsSnapshotReader implements SnapshotReader {
    public static final int MAX_NUM_SMR_ENTRY = 5;
    private long snapshotTimestamp;
    private Set<String> streams;
    private PriorityQueue<String> streamsToSend;
    private CorfuRuntime rt;
    private long preMsgTs;
    private long currentMsgTs;
    private OpaqueStreamIterator currentStreamInfo;
    private long sequence;

    /**
     * Init runtime and streams to read
     */
    public StreamsSnapshotReader(CorfuRuntime rt, LogReplicationConfig config) {
        this.rt = rt;
        streams = config.getStreamsToReplicate();
    }

    /**
     * Given a streamID and list of smrEntries, generate an OpaqueEntry
     * @param streamID
     * @param smrEntries
     * @return
     */
    OpaqueEntry generateOpaqueEntry(long version, UUID streamID, List smrEntries) {
        Map<UUID, List<SMREntry>> map = new HashMap<>();
        map.put(streamID, smrEntries);
        return new OpaqueEntry(version, map);
    }

    /**
     * Given a list of entries with the same stream, will generate an OpaqueEntry and
     * use the opaque entry to generate a TxMessage.
     * @param stream
     * @param entries
     * @return
     */
    LogReplicationEntry generateMessage(OpaqueStreamIterator stream, List<SMREntry> entries, UUID snapshotRequestId) {
        currentMsgTs = stream.maxVersion;
        OpaqueEntry opaqueEntry = generateOpaqueEntry(currentMsgTs, stream.uuid, entries);
        if (!stream.iterator.hasNext()) {
            //mark the end of the current stream.
            currentMsgTs = snapshotTimestamp;
        }

        ByteBuf buf = Unpooled.buffer();
        OpaqueEntry.serialize(buf, opaqueEntry);

        LogReplicationEntry txMsg = new LogReplicationEntry(MessageType.SNAPSHOT_MESSAGE, snapshotRequestId, currentMsgTs,
                preMsgTs, snapshotTimestamp, sequence, buf.array());
        preMsgTs = currentMsgTs;
        sequence++;
        log.debug("Generate TxMsg {}", txMsg.getMetadata());
        return txMsg;
    }

    /**
     * Read numEntries from the current stream.
     * @param stream
     * @param numEntries
     * @return
     */
    List<SMREntry> next(OpaqueStreamIterator stream, int numEntries) {
        //if it is the end of the stream, set an end of stream mark, the current
        List<SMREntry> list = new ArrayList<>();
        try {
            while (stream.iterator.hasNext() && list.size() < numEntries) {
                OpaqueEntry entry = (OpaqueEntry) stream.iterator.next();
                stream.maxVersion = Math.max(stream.maxVersion, entry.getVersion());
                list.addAll(entry.getEntries().get(stream.uuid));
            }
        } catch (TrimmedException e) {
            log.error("Catch an TrimmedException exception ", e);
            throw e;
        }
        return list;
    }

    /**
     * Poll the current stream and get a batch of SMR entries and
     * generate one message
     * @param stream bookkeeping of the current stream information.
     * @return
     */
    LogReplicationEntry read(OpaqueStreamIterator stream, UUID syncRequestId) {
        List<SMREntry> entries = next(stream, MAX_NUM_SMR_ENTRY);
        LogReplicationEntry txMsg = generateMessage(stream, entries, syncRequestId);
        log.info("Successfully pass stream {} for snapshotTimestamp {}", stream.name, snapshotTimestamp);
        return txMsg;
    }

    /**
     * If currentStreamInfo is null that is the case for the first call of read, it will init currentStreamInfo.
     * If the currentStreamInfo ends, poll the next stream.
     * Otherwise, continue to process the current stream.
     * @return
     */
    @Override
    public SnapshotReadMessage read(UUID syncRequestId) {

        boolean endSnapshotSync = false;
        List msgs = new ArrayList<DataMessage>();

        // If the currentStreamInfo still has entry to process, it will reuse the currentStreamInfo
        // and process the remaining entries.
        if (currentStreamInfo == null) {
            while (!streamsToSend.isEmpty()) {
                // Setup a new stream
                currentStreamInfo = new OpaqueStreamIterator(streamsToSend.poll(), rt, snapshotTimestamp);

                // If the new stream has entries to be processed, go to the next step
                if (currentStreamInfo.iterator.hasNext()) {
                    break;
                } else {
                    // Skip process this stream as it has no entries to process, will poll the next one.
                    log.info("Snapshot reader will skip reading stream {} as there are no entries to send",
                            currentStreamInfo.uuid);
                }
            }
        }

        if (currentStreamInfo.iterator.hasNext()) {
            msgs.add(read(currentStreamInfo, syncRequestId));
        }

        if (!currentStreamInfo.iterator.hasNext()) {
            log.debug("Snapshot reader finished reading stream {}", currentStreamInfo.uuid);
            currentStreamInfo = null;

            if (streamsToSend.isEmpty()) {
                log.info("Snapshot reader finished reading all streams {}", streams);
                endSnapshotSync = true;
            }
        }

        return new SnapshotReadMessage(msgs, endSnapshotSync);
    }

    @Override
    public void reset(long snapshotTimestamp) {
        streamsToSend = new PriorityQueue<>(streams);
        preMsgTs = Address.NON_ADDRESS;
        currentMsgTs = Address.NON_ADDRESS;
        this.snapshotTimestamp = snapshotTimestamp; //rt.getAddressSpaceView().getLogTail();
        currentStreamInfo = null;
        sequence = 0;
    }

    /**
     * Used to bookkeeping the stream information for the current processing stream
     */
    public static class OpaqueStreamIterator {
        String name;
        UUID uuid;
        Iterator iterator;
        long maxVersion; //the max address of the log entries processed for this stream.

        OpaqueStreamIterator(String name, CorfuRuntime rt, long snapshot) {
            this.name = name;
            uuid = CorfuRuntime.getStreamID(name);
            StreamOptions options = StreamOptions.builder()
                    .ignoreTrimmed(false)
                    .cacheEntries(false)
                    .build();
            Stream stream = (new OpaqueStream(rt, rt.getStreamsView().get(uuid, options))).streamUpTo(snapshot);
            iterator = stream.iterator();
            maxVersion = 0;
         }
    }
}