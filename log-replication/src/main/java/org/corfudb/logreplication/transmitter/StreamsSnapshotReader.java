package org.corfudb.logreplication.transmitter;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.SerializationUtils;
import org.corfudb.logreplication.MessageType;
import org.corfudb.logreplication.fsm.LogReplicationConfig;
import org.corfudb.logreplication.fsm.LogReplicationContext;
import org.corfudb.logreplication.fsm.LogReplicationEvent;
import org.corfudb.protocols.logprotocol.SMREntry;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.collections.CorfuTable;
import org.corfudb.runtime.exceptions.TrimmedException;
import org.corfudb.runtime.object.StreamViewSMRAdapter;
import org.corfudb.runtime.view.Address;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
/**
 * The AR will call sync API and pass in the api for fullSyncDone(), handlerMsg()
 * Read streams one by one, get all the entries.
 */
public class StreamsSnapshotReader implements SnapshotReader {
    //Todo: will change the max_batch_size while Maithem finish the new API
    private final int MAX_BATCH_SIZE = 1;
    private final MessageType MSG_TYPE = MessageType.SNAPSHOT_MESSAGE;
    private long globalSnapshot;
    private Set<String> streams;
    private CorfuRuntime rt;
    private long preMsgTs;
    private long currentMsgTs;
    private SnapshotListener snapshotListener;
    private LogReplicationConfig config;

    /**
     * Set runtime and callback function to pass message to network
     */
    public StreamsSnapshotReader(CorfuRuntime rt, SnapshotListener snapshotListener, LogReplicationConfig config) {
        this.rt = rt;
        this.snapshotListener = snapshotListener;
        this.config = config;
    }
    /**
     * setup globalSnapshot
     */
    void setup() {
        preMsgTs = Address.NON_ADDRESS;
        currentMsgTs = Address.NON_ADDRESS;
        globalSnapshot = rt.getAddressSpaceView().getLogTail();
    }

    /**
     * get all entries for a stream up to the globalSnapshot
     * @param streamID
     * @return
     */
    List<SMREntry> readStream(UUID streamID) {
        StreamViewSMRAdapter smrAdapter =  new StreamViewSMRAdapter(rt, rt.getStreamsView().getUnsafe(streamID));
        return smrAdapter.remainingUpTo(globalSnapshot);
    }

    TxMessage generateMessage(List<SMREntry> entries) {
        currentMsgTs = entries.get(entries.size() - 1).getGlobalAddress();
        TxMessage txMsg = new TxMessage(MSG_TYPE, currentMsgTs, preMsgTs, globalSnapshot);

        // todo: using Maithem API to generate msg data with entries.
        ByteBuf buf = Unpooled.buffer();
        entries.get(0).serialize(buf);
        txMsg.setData(buf.array());

        log.debug("Generate TxMsg {}", txMsg.getMetadata());
        //set data, will plug in meithem's new api
        return  txMsg;
    }

    /**
     * Given a stream name, get all entries for this stream,
     * put entries in a message and call the callback handler
     * to pass the message to the other site.
     * @param streamName
     */
    void next(String streamName) {
        UUID streamID = CorfuRuntime.getStreamID(streamName);
        ArrayList<SMREntry> entries = new ArrayList<>(readStream(streamID));
        preMsgTs = Address.NON_ADDRESS;

        for (int i = 0; i < entries.size(); i += MAX_BATCH_SIZE) {
            List<SMREntry> msg_entries = entries.subList(i, i + MAX_BATCH_SIZE);
            TxMessage txMsg = generateMessage(msg_entries);

            //update preMsgTs only after process a msg successfully
            preMsgTs = currentMsgTs;
            log.debug("Successfully pass a TxMsg {}", txMsg.getMetadata());
        }

        log.info("Successfully pass a stream {} for globalSnapshot {}", streamName, globalSnapshot);
        return;
    }

    /**
     * while sync finish put an event to the queue
     */
    @Override
    public void sync() {
        setup();
        try {
            for (String streamName : streams) {
                next(streamName);
            }
        } catch (Exception e) {
            //handle exception
            log.warn("Sync call get an exception ", e);
            throw e;
        }

        //todo: update metadata to record a Snapshot Reader done
        log.info("Successfully do a sync read for globalSnapshot {}", globalSnapshot);
    }
}
