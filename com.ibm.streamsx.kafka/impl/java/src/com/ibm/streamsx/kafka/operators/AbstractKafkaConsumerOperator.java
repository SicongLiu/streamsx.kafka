package com.ibm.streamsx.kafka.operators;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.log4j.Logger;

import com.google.common.primitives.Ints;
import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.state.Checkpoint;
import com.ibm.streams.operator.state.ConsistentRegionContext;
import com.ibm.streams.operator.types.RString;
import com.ibm.streams.operator.types.ValueFactory;
import com.ibm.streamsx.kafka.clients.consumer.KafkaConsumerClient;
import com.ibm.streamsx.kafka.clients.consumer.StartPosition;
import com.ibm.streamsx.kafka.i18n.Messages;
import com.ibm.streamsx.kafka.properties.KafkaOperatorProperties;

public abstract class AbstractKafkaConsumerOperator extends AbstractKafkaOperator {
    private static final Logger logger = Logger.getLogger(KafkaConsumerOperator.class);
    private static final Long DEFAULT_CONSUMER_TIMEOUT = 100l;
    private static final Long SHUTDOWN_TIMEOUT = 5l;
    private static final TimeUnit SHUTDOWN_TIMEOUT_TIMEUNIT = TimeUnit.SECONDS;
    private static final StartPosition DEFAULT_START_POSITION = StartPosition.End;
    private static final String DEFAULT_OUTPUT_MESSAGE_ATTR_NAME = "message"; //$NON-NLS-1$
    private static final String DEFAULT_OUTPUT_KEY_ATTR_NAME = "key"; //$NON-NLS-1$
    private static final String DEFAULT_OUTPUT_TOPIC_ATTR_NAME = "topic"; //$NON-NLS-1$
    private static final String OUTPUT_KEY_ATTRIBUTE_NAME_PARAM = "outputKeyAttributeName"; //$NON-NLS-1$
    private static final String OUTPUT_MESSAGE_ATTRIBUTE_NAME_PARAM = "outputMessageAttributeName"; //$NON-NLS-1$
    private static final String OUTPUT_TOPIC_ATTRIBUTE_NAME_PARAM = "outputTopicAttributeName"; //$NON-NLS-1$
    private static final String TRIGGER_COUNT_PARAM = "triggerCount"; //$NON-NLS-1$
    
    private Thread processThread;
    private KafkaConsumerClient consumer;
    private AtomicBoolean shutdown;

    /* Parameters */
    private String outputKeyAttrName = DEFAULT_OUTPUT_KEY_ATTR_NAME;
    private String outputMessageAttrName = DEFAULT_OUTPUT_MESSAGE_ATTR_NAME;
    private String outputTopicAttrName = DEFAULT_OUTPUT_TOPIC_ATTR_NAME;
    private List<String> topics;
    private List<Integer> partitions;
    private StartPosition startPosition = DEFAULT_START_POSITION;
    private int triggerCount;
    private String clientId;

    private Long consumerPollTimeout = DEFAULT_CONSUMER_TIMEOUT;
    private CountDownLatch resettingLatch;
    private boolean hasOutputTopic;
    private boolean hasOutputKey;
    private Integer tupleCounter = 0;

    @Parameter(optional = true, name="clientId",
    		description="Specifies the client ID that should be used "
    				+ "when connecting to the Kafka cluster. The value "
    				+ "specified by this parameter will override the `client.id` "
    				+ "Kafka parameter if specified. If this parameter is not "
    				+ "specified and the the `client.id` Kafka property is not "
    				+ "specified, the operator will use a random client ID.")
    public void setClientId(String clientId) {
		this.clientId = clientId;
	}
    
    @Parameter(optional = true, name="startPosition", 
    		description="Specifies whether the operator should start "
    				+ "reading from the end of the topic, or start reading "
    				+ "all messages from the beginning of the topic. Valid "
    				+ "options include: `Beginning`, `End`. If not specified, "
    				+ "the default value is `End`.")
    public void setStartPosition(StartPosition startPosition) {
        this.startPosition = startPosition;
    }

    @Parameter(optional = true, name="partition",
    		description="Specifies the partitions that the consumer should be "
    				+ "assigned to for each of the topics specified. It should "
    				+ "be noted that using this parameter will *assign* the "
    				+ "consumer to the specified topics, rather than *subscribe* "
    				+ "to them. This implies that the consumer will not use Kafka's "
    				+ "group management feature.")
    public void setPartitions(int[] partitions) {
    	this.partitions = Ints.asList(partitions);
	}
    
    @Parameter(optional = false, name="topic",
    		description="Specifies the topic or topics that the consumer should "
    				+ "subscribe to. To assign the consumer to specific partitions, "
    				+ "use the **partitions** parameter.")
    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    @Parameter(optional = true, name=OUTPUT_KEY_ATTRIBUTE_NAME_PARAM,
    		description="Specifies the output attribute name that should contain "
    				+ "the key. If not specified, the operator will attempt to "
    				+ "store the message in an attribute named 'key'.")
    public void setOutputKeyAttrName(String outputKeyAttrName) {
        this.outputKeyAttrName = outputKeyAttrName;
    }

    @Parameter(optional = true, name=OUTPUT_MESSAGE_ATTRIBUTE_NAME_PARAM,
    		description="Specifies the output attribute name that will contain the "
    				+ "message. If not specified, the operator will attempt to store "
    				+ "the message in an attribute named 'message'.")
    public void setOutputMessageAttrName(String outputMessageAttrName) {
        this.outputMessageAttrName = outputMessageAttrName;
    }

    @Parameter(optional = true, name=OUTPUT_TOPIC_ATTRIBUTE_NAME_PARAM,
    		description="Specifies the output attribute name that should contain the topic. "
    				+ "If not specified, the operator will attempt to store the message in "
    				+ "an attribute named 'topic'.")
    public void setOutputTopicAttrName(String outputTopicAttrName) {
        this.outputTopicAttrName = outputTopicAttrName;
    }

    @Parameter(optional = true, name=TRIGGER_COUNT_PARAM, 
    		description="This parameter specifies the approximate number of messages that will "
    				+ "be submitted to the output port before initiating a checkpoint. The "
    				+ "operator retrieves batches of messages from Kafka, and the consistent "
    				+ "region is only started after all messages in the batch have been submitted. "
    				+ "The implication of this is that more tuples may be submitted by the operator "
    				+ "before a consistent region is triggered. This parameter is only used if the "
    				+ "operator is the start of a consistent region.")
    public void setTriggerCount(int triggerCount) {
        this.triggerCount = triggerCount;
    }

    @ContextCheck(compile = false, runtime = true)
    public static void checkMessageParam(OperatorContextChecker checker) {
        StreamSchema streamSchema = checker.getOperatorContext().getStreamingOutputs().get(0).getStreamSchema();
        Set<String> paramNames = checker.getOperatorContext().getParameterNames();

        String messageAttrName = paramNames.contains(OUTPUT_MESSAGE_ATTRIBUTE_NAME_PARAM) ? 
                checker.getOperatorContext().getParameterValues(OUTPUT_MESSAGE_ATTRIBUTE_NAME_PARAM).get(0) //$NON-NLS-1$
                : DEFAULT_OUTPUT_MESSAGE_ATTR_NAME;

        // set invalid context if message attribute name does not exist
        Attribute messageAttr = streamSchema.getAttribute(messageAttrName);
        if (messageAttr == null) {
            checker.setInvalidContext(Messages.getString("OUTPUT_MESSAGE_ATTRIBUTE_MISSING"), new Object[0]); //$NON-NLS-1$
        } else {
            // validate the attribute type
            checker.checkAttributeType(messageAttr, SUPPORTED_ATTR_TYPES);
        }
        
        // check that user-specified key attr name exists
        Attribute keyAttr;
        if (paramNames.contains(OUTPUT_KEY_ATTRIBUTE_NAME_PARAM)) {
            String keyAttrName = checker.getOperatorContext().getParameterValues(OUTPUT_KEY_ATTRIBUTE_NAME_PARAM).get(0);
            keyAttr = streamSchema.getAttribute(keyAttrName);
            if (keyAttr == null) {
                checker.setInvalidContext(Messages.getString("OUTPUT_ATTRIBUTE_NOT_FOUND", keyAttrName), new Object[0]); //$NON-NLS-1$
            }
        } else {
            keyAttr = streamSchema.getAttribute(DEFAULT_OUTPUT_KEY_ATTR_NAME);
        }

        // validate the attribute type
        if (keyAttr != null)
            checker.checkAttributeType(keyAttr, SUPPORTED_ATTR_TYPES);

        // check that the user-specified topic attr name exists
        Attribute topicAttr;
        if (paramNames.contains(OUTPUT_TOPIC_ATTRIBUTE_NAME_PARAM)) {
            String topicAttrName = checker.getOperatorContext().getParameterValues(OUTPUT_TOPIC_ATTRIBUTE_NAME_PARAM).get(0);
            topicAttr = streamSchema.getAttribute(topicAttrName);
            if (topicAttr == null) {
                checker.setInvalidContext(Messages.getString("OUTPUT_ATTRIBUTE_NOT_FOUND", topicAttrName), //$NON-NLS-1$
                        new Object[0]);
            }
        }
    }

    @ContextCheck(compile = true)
    public static void checkTriggerCount(OperatorContextChecker checker) {
        ConsistentRegionContext crContext = checker.getOperatorContext()
                .getOptionalContext(ConsistentRegionContext.class);
        if (crContext != null) {
            if (crContext.isStartOfRegion() && crContext.isTriggerOperator()) {
                if (!checker.getOperatorContext().getParameterNames().contains(TRIGGER_COUNT_PARAM)) {
                    checker.setInvalidContext(Messages.getString("TRIGGER_PARAM_MISSING"), new Object[0]); //$NON-NLS-1$
                }
            }
        }
    }

    @Override
    public synchronized void initialize(OperatorContext context) throws Exception {
        // Must call super.initialize(context) to correctly setup an operator.
        super.initialize(context);
        logger.trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + context.getPE().getJobId());
        shutdown = new AtomicBoolean(false);

        StreamSchema outputSchema = context.getStreamingOutputs().get(0).getStreamSchema();
        hasOutputKey = outputSchema.getAttribute(outputKeyAttrName) != null;
        hasOutputTopic = outputSchema.getAttribute(outputTopicAttrName) != null;
        
        Class<?> keyClass = hasOutputKey ? getAttributeType(context.getStreamingOutputs().get(0), outputKeyAttrName)
                : String.class; // default to String.class for key type
        Class<?> valueClass = getAttributeType(context.getStreamingOutputs().get(0), outputMessageAttrName);
        KafkaOperatorProperties kafkaProperties = getKafkaProperties();
        logger.debug("kafkaProperties: " + kafkaProperties); //$NON-NLS-1$

        // set the client ID property if the clientId parameter is specified
        if(clientId != null && !clientId.isEmpty()) {
        	kafkaProperties.setProperty(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        }
        
        if (topics != null)
            registerForDataGovernance(context, topics);

        consumer = new KafkaConsumerClient.KafkaConsumerClientBuilder()
        			.setKafkaProperties(kafkaProperties)
        			.setKeyClass(keyClass)
        			.setValueClass(valueClass)
        			.setOperatorContext(context)
        			.setTopics(topics)
        			.setPartitions(partitions)
        			.setStartPosition(startPosition)
        			.build();

        crContext = context.getOptionalContext(ConsistentRegionContext.class);
        if (crContext != null && context.getPE().getRelaunchCount() > 0) {
            resettingLatch = new CountDownLatch(1);
        }

        processThread = getOperatorContext().getThreadFactory().newThread(new Runnable() {

            @Override
            public void run() {
                try {
                    produceTuples();
                } catch (Exception e) {
                    Logger.getLogger(this.getClass()).error("Operator error", e); //$NON-NLS-1$
                }
            }

        });

        processThread.setDaemon(false);
    }

    @Override
    public synchronized void allPortsReady() throws Exception {
        OperatorContext context = getOperatorContext();
        logger.trace("Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() //$NON-NLS-1$ //$NON-NLS-2$
                + " in Job: " + context.getPE().getJobId()); //$NON-NLS-1$

        processThread.start();
    }

    private void produceTuples() throws Exception {
        if (crContext != null && resettingLatch != null) {
            logger.debug("Operator is in the middle of resetting. No tuples will be submitted until reset completes."); //$NON-NLS-1$
            resettingLatch.await();
        }

        consumer.sendStartPollingEvent(consumerPollTimeout);
        while (!shutdown.get()) {
            try {
                if (crContext != null) {
                    logger.trace("Acquiring consistent region permit..."); //$NON-NLS-1$
                    crContext.acquirePermit();
                }

                logger.trace("Polling for messages, timeout=" + consumerPollTimeout); //$NON-NLS-1$
                ConsumerRecords<?, ?> records = consumer.getRecords();
                if (records != null) {
                    submitRecords(records);
                }

                if (crContext != null && crContext.isTriggerOperator()) {
                    if (tupleCounter >= triggerCount) {
                        logger.debug("Making region consistent..."); //$NON-NLS-1$
                        boolean isSuccess = crContext.makeConsistent();
                        tupleCounter = 0;
                        logger.debug("Completed call to makeConsistent: isSuccess=" + isSuccess); //$NON-NLS-1$
                    }
                }
            } catch (InterruptedException e) {
                logger.error(Messages.getString("ERROR_ACQUIRING_PERMIT", e.getLocalizedMessage())); //$NON-NLS-1$
                e.printStackTrace();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                if (crContext != null) {
                    logger.trace("Releasing consistent region permit..."); //$NON-NLS-1$
                    crContext.releasePermit();
                }
            }
        }
        consumer.sendStopPollingEvent();
    }

    private void submitRecords(ConsumerRecords<?, ?> records) throws Exception {
        final StreamingOutput<OutputTuple> out = getOutput(0);
        //logger.trace("Preparing to submit " + records.count() + " tuples"); //$NON-NLS-1$ //$NON-NLS-2$
        Iterator<?> it = records.iterator();
        while (it.hasNext()) {
            ConsumerRecord<?, ?> record = (ConsumerRecord<?, ?>) it.next();

            OutputTuple tuple = out.newTuple();
            setTuple(tuple, outputMessageAttrName, record.value());

            if (hasOutputKey) {
                setTuple(tuple, outputKeyAttrName, record.key());
            }

            if (hasOutputTopic) {
                tuple.setString(outputTopicAttrName, record.topic());
            }

            //logger.debug("Submitting tuple: " + tuple); //$NON-NLS-1$
            out.submit(tuple);
            tupleCounter++;
        }
    }

    private void setTuple(OutputTuple tuple, String attrName, Object attrValue) throws Exception {
    	if(attrValue == null)
    		return; // do nothing
    	
        if (attrValue instanceof String || attrValue instanceof RString)
            tuple.setString(attrName, (String) attrValue);
        else if (attrValue instanceof Integer)
            tuple.setInt(attrName, (Integer) attrValue);
        else if (attrValue instanceof Double || attrValue instanceof Float)
            tuple.setDouble(attrName, (Double) attrValue);
        else if (attrValue instanceof Long)
            tuple.setLong(attrName, (Long) attrValue);
        else if (attrValue instanceof Byte)
            tuple.setByte(attrName, (Byte) attrValue);
        else if (attrValue instanceof byte[])
            tuple.setBlob(attrName, ValueFactory.newBlob((byte[]) attrValue));
        else
            throw new Exception(Messages.getString("UNSUPPORTED_TYPE_EXCEPTION", (attrValue.getClass().getTypeName()), attrName)); //$NON-NLS-1$
    }

    /**
     * Shutdown this operator, which will interrupt the thread executing the
     * <code>produceTuples()</code> method.
     * 
     * @throws Exception
     *             Operator failure, will cause the enclosing PE to terminate.
     */
    public synchronized void shutdown() throws Exception {
        // send shutdown signal and wait for thread to complete,
        // otherwise interrupt the thread
        shutdown.set(true);
        if (processThread != null) {
            processThread.join(5000);
            if (processThread != null && processThread.isAlive()) {
                processThread.interrupt();
            }
            processThread = null;
        }
        consumer.sendShutdownEvent(SHUTDOWN_TIMEOUT, SHUTDOWN_TIMEOUT_TIMEUNIT);

        OperatorContext context = getOperatorContext();
        logger.trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() //$NON-NLS-1$ //$NON-NLS-2$
                + " in Job: " + context.getPE().getJobId()); //$NON-NLS-1$

        // Must call super.shutdown()
        super.shutdown();
    }

    @Override
    public void drain() throws Exception {
        logger.debug("Draining..."); //$NON-NLS-1$
        super.drain();
        // send all records in buffer
        consumer.sendStopPollingEvent();
        ConsumerRecords<?, ?> records;

        logger.trace("Submitting remaining records from buffer..."); //$NON-NLS-1$
        while ((records = consumer.getRecords()) != null) {
            submitRecords(records);
        }
    }

    @Override
    public void checkpoint(Checkpoint checkpoint) throws Exception {
        super.checkpoint(checkpoint);
        consumer.sendCheckpointEvent(checkpoint); // blocks until checkpoint completes
        consumer.sendStartPollingEvent(consumerPollTimeout); // checkpoint is done, resume polling for records
    }

    @Override
    public void reset(Checkpoint checkpoint) throws Exception {
        super.reset(checkpoint);
        consumer.sendResetEvent(checkpoint); // blocks until reset completes
        consumer.sendStartPollingEvent(consumerPollTimeout); // done resetting,start polling for records

        resettingLatch.countDown();
    }

    @Override
    public void resetToInitialState() throws Exception {
        super.resetToInitialState();
        consumer.sendResetToInitEvent(); // blocks until resetToInit completes
        consumer.sendStartPollingEvent(consumerPollTimeout); // done resettings, start polling for records

        resettingLatch.countDown();
    }
}