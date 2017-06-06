package com.ibm.streamsx.kafka.operators;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.StopWatch;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.log4j.Logger;

import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.state.Checkpoint;
import com.ibm.streams.operator.state.ConsistentRegionContext;
import com.ibm.streamsx.kafka.PerformanceLevel;
import com.ibm.streamsx.kafka.clients.producer.AtLeastOnceKafkaProducerClient;
import com.ibm.streamsx.kafka.clients.producer.KafkaProducerClient;
import com.ibm.streamsx.kafka.i18n.Messages;
import com.ibm.streamsx.kafka.properties.KafkaOperatorProperties;

public abstract class AbstractKafkaProducerOperator extends AbstractKafkaOperator {
    private static final String DEFAULT_MESSAGE_ATTR_NAME = "message"; //$NON-NLS-1$
    private static final String DEFAULT_KEY_ATTR_NAME = "key"; //$NON-NLS-1$
    private static final String DEFAULT_TOPIC_ATTR_NAME = "topic"; //$NON-NLS-1$

    private static final Logger logger = Logger.getLogger(KafkaProducerOperator.class);

    /* Parameters */
    protected String messageAttrName = DEFAULT_MESSAGE_ATTR_NAME;
    protected String keyAttrName = DEFAULT_KEY_ATTR_NAME;
    protected List<String> topics;
    protected String topicAttrName = DEFAULT_TOPIC_ATTR_NAME;

    private KafkaProducerClient producer;
    private boolean isResetting;
    private boolean hasKeyAttr;

    @Parameter(optional = true)
    public void setMessageAttrName(String messageAttrName) {
        this.messageAttrName = messageAttrName;
    }

    @Parameter(optional = true)
    public void setKeyAttrName(String keyAttrName) {
        this.keyAttrName = keyAttrName;
    }

    @Parameter(optional = true)
    public void setTopic(List<String> topics) {
        this.topics = topics;
    }

    @Parameter(optional = true)
    public void setTopicAttrName(String topicAttrName) {
        this.topicAttrName = topicAttrName;
    }

    @ContextCheck(runtime = true, compile = false)
    public static void checkAttributesExist(OperatorContextChecker checker) {
        StreamSchema streamSchema = checker.getOperatorContext().getStreamingInputs().get(0).getStreamSchema();

        List<String> messageParamValues = checker.getOperatorContext().getParameterValues("messageAttrName"); //$NON-NLS-1$
        String messageAttrName = (messageParamValues != null && !messageParamValues.isEmpty())
                ? messageParamValues.get(0) : DEFAULT_MESSAGE_ATTR_NAME;
        Attribute messageAttr = streamSchema.getAttribute(messageAttrName);
        if (messageAttr == null) {
            checker.setInvalidContext(Messages.getString("INPUT_ATTRIBUTE_NOT_FOUND", messageAttrName), new Object[0]); //$NON-NLS-1$
        }

        // validate the message attribute type
        checker.checkAttributeType(messageAttr, SUPPORTED_ATTR_TYPES);

        List<String> keyParamValues = checker.getOperatorContext().getParameterValues("keyAttrName"); //$NON-NLS-1$
        Attribute keyAttr;
        if (keyParamValues != null && !keyParamValues.isEmpty()) {
            String keyAttrName = keyParamValues.get(0);
            keyAttr = streamSchema.getAttribute(keyAttrName);
            if (keyAttr == null) {
                checker.setInvalidContext(Messages.getString("INPUT_ATTRIBUTE_NOT_FOUND", keyAttrName), new Object[0]); //$NON-NLS-1$
            }
        } else {
            keyAttr = streamSchema.getAttribute(DEFAULT_KEY_ATTR_NAME);
        }

        // validate the key attribute type
        if (keyAttr != null)
            checker.checkAttributeType(keyAttr, SUPPORTED_ATTR_TYPES);

        // if the topic param is not defined, then "topicAttrName"
        // must point to an existing attribute on the input schema
        if (!checker.getOperatorContext().getParameterNames().contains("topic")) { //$NON-NLS-1$
            // check if the attribute name specified by the "topicAttrName"
            // exists
            String topicAttrName = checker.getOperatorContext().getParameterNames().contains("topicAttrName") ? //$NON-NLS-1$
                    checker.getOperatorContext().getParameterValues("topicAttrName").get(0) : DEFAULT_TOPIC_ATTR_NAME; //$NON-NLS-1$

            Attribute topicAttr = streamSchema.getAttribute(topicAttrName);
            if (topicAttr == null) {
                checker.setInvalidContext(Messages.getString("TOPIC_NOT_SPECIFIED"), new Object[0]); //$NON-NLS-1$
            }
        }
    }

    /**
     * Initialize this operator. Called once before any tuples are processed.
     * 
     * @param context
     *            OperatorContext for this operator.
     * @throws Exception
     *             Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void initialize(OperatorContext context) throws Exception {
        super.initialize(context);
        logger.trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + context.getPE().getJobId());

        StreamSchema inputSchema = context.getStreamingInputs().get(0).getStreamSchema();
        hasKeyAttr = inputSchema.getAttribute(keyAttrName) != null;

        messageType = getAttributeType(context.getStreamingInputs().get(0), messageAttrName);
        keyType = hasKeyAttr ? getAttributeType(context.getStreamingInputs().get(0), keyAttrName) : String.class; // default
                                                                                                                    // to
                                                                                                                    // String.class
                                                                                                                    // for
                                                                                                                    // key
                                                                                                                    // type
        initProducer();

        registerForDataGovernance(context, topics);

        crContext = context.getOptionalContext(ConsistentRegionContext.class);
        if (crContext != null) {
            isResetting = context.getPE().getRelaunchCount() > 0;
        }

        logger.info(">>> Operator initialized! <<<"); //$NON-NLS-1$
    }

    private void initProducer() throws Exception {
        // configure producer
        KafkaOperatorProperties props = getKafkaProperties();
        logger.info("Creating AtLeastOnce producer");
        producer = new AtLeastOnceKafkaProducerClient(getOperatorContext(), keyType, messageType, props);
    }

    /**
     * Notification that initialization is complete and all input and output
     * ports are connected and ready to receive and submit tuples.
     * 
     * @throws Exception
     *             Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void allPortsReady() throws Exception {
        OperatorContext context = getOperatorContext();
        logger.trace("Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() //$NON-NLS-1$ //$NON-NLS-2$
                + " in Job: " + context.getPE().getJobId()); //$NON-NLS-1$

    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public void process(StreamingInput<Tuple> stream, Tuple tuple) throws Exception {
        if (crContext != null && isResetting) {
            logger.debug("Operator is in the middle of resetting...skipping tuple processing!"); //$NON-NLS-1$
            return;
        }

        List<String> topicList = (this.topics != null && !this.topics.isEmpty()) ? this.topics
                : Arrays.asList(tuple.getString(topicAttrName));
        Object key = hasKeyAttr ? toJavaPrimitveObject(keyType, tuple.getObject(keyAttrName)) : null;
        Object value = toJavaPrimitveObject(messageType, tuple.getObject(messageAttrName));

        // send message to all topics
        for (String topic : topicList)
            producer.processTuple(new ProducerRecord(topic, key, value));
    }

    /**
     * Shutdown this operator, which will interrupt the thread executing the
     * <code>produceTuples()</code> method.
     * 
     * @throws Exception
     *             Operator failure, will cause the enclosing PE to terminate.
     */
    public synchronized void shutdown() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " shutting down in PE: " //$NON-NLS-1$ //$NON-NLS-2$
                + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId()); //$NON-NLS-1$

        producer.flush();
        producer.close();

        // Must call super.shutdown()
        super.shutdown();
    }

    @Override
    public void close() throws IOException {
        // TODO Auto-generated method stub
    }

    @Override
    public void drain() throws Exception {
        logger.debug(">>> DRAIN"); //$NON-NLS-1$
        // flush all records from buffer...
        // if any messages fail to
        // be acknowledged, an exception
        // will be thrown and the
        // region will be reset
        producer.drain();
    }

    @Override
    public void checkpoint(Checkpoint checkpoint) throws Exception {
        logger.debug(">>> CHECKPOINT (ckpt id=" + checkpoint.getSequenceId() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
        producer.checkpoint(checkpoint);
    }

    @Override
    public void reset(Checkpoint checkpoint) throws Exception {
        logger.debug(">>> RESET (ckpt id=" + checkpoint.getSequenceId() + ")"); //$NON-NLS-1$ //$NON-NLS-2$

        /*
         * Close the producer and initialize a new once. Calling close() will
         * flush out all remaining messages and then shutdown the producer.
         */
        producer.close();
        producer = null;
        initProducer();

        logger.debug("Initiating reset..."); //$NON-NLS-1$
        producer.reset(checkpoint);

        // reset complete
        isResetting = false;
        logger.debug("Reset complete!"); //$NON-NLS-1$
    }

    @Override
    public void resetToInitialState() throws Exception {
        logger.debug(">>> RESET TO INIT..."); //$NON-NLS-1$

        initProducer();
        producer.resetToInitialState();
        isResetting = false;
    }

    @Override
    public void retireCheckpoint(long id) throws Exception {
        logger.debug(">>> RETIRE CHECKPOINT: " + id); //$NON-NLS-1$
    }

    /*
     * FOR DEBUGGING!!
     */
    @SuppressWarnings("unused")
    private void printExecutionTime(String methodName, StopWatch sw) {
        logger.log(PerformanceLevel.PERF,
                String.format("%s time: %d ms", methodName, sw.getTime(TimeUnit.MILLISECONDS))); //$NON-NLS-1$
    }
}