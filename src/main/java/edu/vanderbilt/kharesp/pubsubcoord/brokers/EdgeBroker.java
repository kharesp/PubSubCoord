package edu.vanderbilt.kharesp.pubsubcoord.brokers;

import java.net.InetAddress;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.zookeeper.CreateMode;
import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.domain.DomainParticipantFactoryQos;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.publication.builtin.PublicationBuiltinTopicDataDataReader;
import com.rti.dds.publication.builtin.PublicationBuiltinTopicDataTypeSupport;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.builtin.SubscriptionBuiltinTopicDataDataReader;
import com.rti.dds.subscription.builtin.SubscriptionBuiltinTopicDataTypeSupport;

public class EdgeBroker {
	// Domain id in which Routing Brokers operate in the cloud
	public static final int WAN_DOMAIN_ID = 230;
	// Default domain id for each region
	public static final int DEFAULT_DOMAIN_ID=0;
	// Domain id in which publishers operate in this local domain
	public static final int PUB_DOMAIN_ID=0;
	// Domain id in which subscribers operate in this local domain
	public static final int SUB_DOMAIN_ID=1;
    // public facing port for sending/receiving data for this local domain
    public static final String EB_P2_BIND_PORT = "8502";
    public static final String EB_P2_PUB_BIND_PORT = "8502";
    public static final String EB_P2_SUB_BIND_PORT = "8503";

    private static final String DOMAIN_ROUTE_NAME_PREFIX = "EdgeBrokerDomainRoute";
    private static final String LOCAL_DOMAIN_ROUTE_NAME_PREFIX = "LocalEdgeBrokerDomainRoute";
    private static final String PUB_DOMAIN_ROUTE_NAME_PREFIX = "PubEdgeBrokerDomainRoute";
    private static final String SUB_DOMAIN_ROUTE_NAME_PREFIX = "SubEdgeBrokerDomainRoute";

    private String ebAddress;
    private String domainRouteName; 
    private String localDomainRouteName;
    private String pubDomainRouteName;
    private String subDomainRouteName;
    
    
    private boolean emulated_broker;
    private String zkConnector;
    private RoutingServiceAdministrator rs;
    private CuratorFramework client = null;
    private Logger logger;

    public EdgeBroker(String zkConnector,boolean emulated_broker){
    	this.zkConnector=zkConnector;
    	this.emulated_broker=emulated_broker;
    	logger=Logger.getLogger(this.getClass().getSimpleName());

        try {
             ebAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (java.net.UnknownHostException e) {
            System.out.println("Host address is not known");
        }

        // Create Routing Service remote administrator 
        try {
			rs=new RoutingServiceAdministrator(ebAddress);
		} catch (Exception e) {
            logger.error(e.getMessage(),e);
		}
    }
    public static void main(String args[]){
    	if (args.length<2){
    		System.out.println("Enter zkConnector (address:port), emulated_broker(0/1)");
    		return;
    	}
    	String zkConnector=args[0];
    	int emulated_broker=Integer.parseInt(args[1]);
    	PropertyConfigurator.configure("log4j.properties");
    	new EdgeBroker(zkConnector,emulated_broker>0?true:false).start();
    }
    
    public void start(){
    	logger.debug(String.format("Starting EdgeBroker:%s\n",ebAddress));
    	// Create a domain route between local domain and wan domain in which RBs operate 
    	domainRouteName=DOMAIN_ROUTE_NAME_PREFIX + "@"+ ebAddress;
    	localDomainRouteName=LOCAL_DOMAIN_ROUTE_NAME_PREFIX + "@"+ ebAddress;
    	pubDomainRouteName=PUB_DOMAIN_ROUTE_NAME_PREFIX + "@"+ ebAddress;
    	subDomainRouteName=SUB_DOMAIN_ROUTE_NAME_PREFIX + "@"+ ebAddress;

    	if (emulated_broker){
    		createLocalDomainRoute();
    		createSubDomainRoute();
    		createPubDomainRoute();
    		
    	}else{
    		createDomainRoute();
    	}
    	
    	// Connect to ZK
    	client = CuratorFrameworkFactory.newClient(zkConnector,
                new ExponentialBackoffRetry(1000, 3));
        client.start();
        
        try{
        	// Ensure /topics path exists 
        	if (client.checkExists().forPath(CuratorHelper.TOPIC_PATH)==null){
        		logger.debug(String.format("zk path %s does not exist. EB: %s creating zk path:%s\n",
        				CuratorHelper.TOPIC_PATH,ebAddress,CuratorHelper.TOPIC_PATH));
        		client.create().withMode(CreateMode.PERSISTENT).forPath(CuratorHelper.TOPIC_PATH, new byte[0]);
        	}
        	// Create built-in entities
        	if(emulated_broker)
        		createBuiltinTopics_brokered();
        	else
        		createBuiltinTopics();

        	while (true) {
        		Thread.sleep(1000);
        	}
        }catch(Exception e){
        	logger.error(e.getMessage(),e);    	
        }
    }
    private void createBuiltinTopics() throws Exception {
		logger.debug(String.format("EdgeBroker:%s installing listeners for builtin topics\n", ebAddress));
		DomainParticipant participant = get_participant_with_disabled_entities(DEFAULT_DOMAIN_ID);
		if (participant == null) {
			throw new Exception(String.format("Builtin Topics Participant creation failed for domain id:%d\n",
					DEFAULT_DOMAIN_ID));
		}
		install_builtin_publisher_listener(participant);
		install_builtin_subscriber_listener(participant);
		participant.enable();
    }
    private void createBuiltinTopics_brokered() throws Exception {
		logger.debug(String.format("EdgeBroker:%s installing listeners for builtin topics\n", ebAddress));
		DomainParticipant participant_for_pub = get_participant_with_disabled_entities(PUB_DOMAIN_ID);
		if (participant_for_pub == null) {
			throw new Exception(String.format("Builtin Topics Participant creation failed for domain id:%d\n",
					PUB_DOMAIN_ID));
		}
		DomainParticipant participant_for_sub = get_participant_with_disabled_entities(SUB_DOMAIN_ID);
		if (participant_for_sub==null) {
			throw new Exception(String.format("Builtin Topics Participant creation failed for domain id:%d\n",
					SUB_DOMAIN_ID));
		}
		install_builtin_publisher_listener(participant_for_pub);
		install_builtin_subscriber_listener(participant_for_sub);
		participant_for_pub.enable();
		participant_for_sub.enable();
    }
    private DomainParticipant get_participant_with_disabled_entities(int domainId) {
		DomainParticipantFactoryQos factory_qos = new DomainParticipantFactoryQos();
		DomainParticipantFactory.TheParticipantFactory.get_qos(factory_qos);
		factory_qos.entity_factory.autoenable_created_entities = false;
		DomainParticipantFactory.TheParticipantFactory.set_qos(factory_qos);
		DomainParticipant participant= DomainParticipantFactory.TheParticipantFactory.create_participant(domainId,
				DomainParticipantFactory.PARTICIPANT_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
		return participant;
    }
    private void install_builtin_publisher_listener(DomainParticipant participant){
    	try{
    		Subscriber builtin_subscriber = participant.get_builtin_subscriber();
			if (builtin_subscriber == null) {
				throw new Exception("Subscriber creation failed");
			}
			// obtain DR to listen for publisher creation
			PublicationBuiltinTopicDataDataReader builtin_publication_datareader = (PublicationBuiltinTopicDataDataReader) builtin_subscriber
					.lookup_datareader(PublicationBuiltinTopicDataTypeSupport.PUBLICATION_TOPIC_NAME);
			if (builtin_publication_datareader == null) {
				throw new Exception("Built-in Publication DataReader creation failed");
			}
			
			logger.debug(String.format("EdgeBroker:%s installing listener for publisher discovery\n",ebAddress));
			// Install listener for Publication discovery
			BuiltinPublisherListener builtin_publisher_listener =
					new BuiltinPublisherListener(ebAddress,client,rs,emulated_broker);
			builtin_publication_datareader.set_listener(builtin_publisher_listener, StatusKind.STATUS_MASK_ALL);
			
    	}catch(Exception e){
    		if (participant != null) {
                participant.delete_contained_entities();
                DomainParticipantFactory.TheParticipantFactory.
                        delete_participant(participant);
            }
    	}
    }
    private void install_builtin_subscriber_listener(DomainParticipant participant){
		try {
			Subscriber builtin_subscriber = participant.get_builtin_subscriber();
			if (builtin_subscriber == null) {
				throw new Exception("Subscriber creation failed");
			}
			// obtian DR to listen for subscriber creation
			SubscriptionBuiltinTopicDataDataReader builtin_subscription_datareader = (SubscriptionBuiltinTopicDataDataReader) builtin_subscriber
					.lookup_datareader(SubscriptionBuiltinTopicDataTypeSupport.SUBSCRIPTION_TOPIC_NAME);
			if (builtin_subscription_datareader == null) {
				throw new IllegalStateException("Built-in Subscription DataReader creation failed");
			}

			logger.debug(String.format("EdgeBroker:%s installing listener for subscriber discovery\n", ebAddress));
			// Install listener for Subscription discovery
			BuiltinSubscriberListener builtin_subscriber_listener = 
					new BuiltinSubscriberListener(ebAddress, client,rs,emulated_broker);
			builtin_subscription_datareader.set_listener(builtin_subscriber_listener, StatusKind.STATUS_MASK_ALL);

		} catch (Exception e) {
			if (participant != null) {
				participant.delete_contained_entities();
				DomainParticipantFactory.TheParticipantFactory.delete_participant(participant);
			}
		}
    }
    private void createDomainRoute(){
    	logger.debug(String.format("EB:%s will create a DomainRoute:%s between local domain id:%d and wan domain id:%d\n",
    			ebAddress,domainRouteName,DEFAULT_DOMAIN_ID,WAN_DOMAIN_ID));
    	
    	rs.createDomainRoute("str://\"<domain_route name=\"" + domainRouteName + "\">" 
    					+ "<entity_monitoring>"
    					+ "<historical_statistics><up_time>true</up_time></historical_statistics>"
    					+ "</entity_monitoring>"
    					+ "<participant_1>" 
    					+ "<domain_id>" 
    					+ DEFAULT_DOMAIN_ID
    					+ "</domain_id>" 
    					+ "<participant_qos>" 
    					+ "<database>"
    					+ "<cleanup_period>"
    					+ "<sec>1</sec>"
    					+ "</cleanup_period>"
    					+ "</database>"
    					+ "<transport_builtin><mask>UDPv4</mask></transport_builtin>" 
    					+ "</participant_qos>" 
    					+ "</participant_1>" 
    					+ "<participant_2>" 
    					+ "<domain_id>" 
    					+ WAN_DOMAIN_ID 
    					+ "</domain_id>"
    					+ "<participant_qos>"
    					+ "<database>"
    					+ "<cleanup_period>"
    					+ "<sec>1</sec>"
    					+ "</cleanup_period>"
    					+ "</database>"
    					+ "<transport_builtin><mask>MASK_NONE</mask></transport_builtin>"
    					+ "<property><value>" 
    					+ "<element><name>dds.transport.load_plugins</name><value>dds.transport.TCPv4.tcp1</value></element>" 
                        + "<element><name>dds.transport.TCPv4.tcp1.library</name><value>nddstransporttcp</value></element>" 
                        + "<element><name>dds.transport.TCPv4.tcp1.create_function</name><value>NDDS_Transport_TCPv4_create</value></element>" 
                        + "<element><name>dds.transport.TCPv4.tcp1.parent.classid</name><value>NDDS_TRANSPORT_CLASSID_TCPV4_WAN</value></element>" 
                        + "<element><name>dds.transport.TCPv4.tcp1.public_address</name><value>" 
                        + ebAddress + ":" + EB_P2_BIND_PORT 
                        + "</value></element>" 
                        + "<element><name>dds.transport.TCPv4.tcp1.server_bind_port</name><value>" 
                        + EB_P2_BIND_PORT 
                        + "</value></element>" 
                        + "</value></property>" 
                        + "</participant_qos>" 
                        + "</participant_2>" 
                        + "</domain_route>\"");
    }
    private void createLocalDomainRoute(){
    	logger.debug(String.format("EB:%s will create a DomainRoute:%s between publisher's local domain id:%d and subscriber's local domain id:%d\n",
    			ebAddress,localDomainRouteName,PUB_DOMAIN_ID,SUB_DOMAIN_ID));
    	rs.createDomainRoute("str://\"<domain_route name=\"" + localDomainRouteName + "\">" 
                + "<entity_monitoring>" 
                + "<historical_statistics><up_time>true</up_time></historical_statistics>" 
                + "</entity_monitoring>" 
                + "<participant_1>" 
                + "<domain_id>" + PUB_DOMAIN_ID+ "</domain_id>" 
                + "<participant_qos>" 
    			+ "<database>"
    			+ "<cleanup_period>"
    			+ "<sec>1</sec>"
    			+ "</cleanup_period>"
    			+ "</database>"
                + "<transport_builtin><mask>UDPv4</mask></transport_builtin>" 
                + "</participant_qos>" 
                + "</participant_1>" 
                + "<participant_2>" 
                + "<domain_id>" + SUB_DOMAIN_ID + "</domain_id>" 
                + "<participant_qos>" 
    			+ "<database>"
    			+ "<cleanup_period>"
    			+ "<sec>1</sec>"
    			+ "</cleanup_period>"
    			+ "</database>"
                + "<transport_builtin><mask>UDPv4</mask></transport_builtin>" 
                + "</participant_qos>" 
                + "</participant_2>" 
                + "</domain_route>\"");
    	
    }
    private void createPubDomainRoute(){
    	logger.debug(String.format("EB:%s will create a DomainRoute:%s between publisher's local domain id:%d and wan domain id:%d\n",
    			ebAddress,pubDomainRouteName,PUB_DOMAIN_ID,WAN_DOMAIN_ID));
    	
    	rs.createDomainRoute("str://\"<domain_route name=\"" + pubDomainRouteName + "\">" 
                         + "<entity_monitoring>" 
                         + "<historical_statistics><up_time>true</up_time></historical_statistics>" 
                         + "</entity_monitoring>" 
                         + "<participant_1>" 
                         + "<domain_id>" + PUB_DOMAIN_ID+ "</domain_id>" 
                         + "<participant_qos>" 
    			         + "<database>"
    			         + "<cleanup_period>"
    			         + "<sec>1</sec>"
    			         + "</cleanup_period>"
    			         + "</database>"
                         + "<transport_builtin><mask>UDPv4</mask></transport_builtin>" 
                         + "</participant_qos>" 
                         + "</participant_1>" 
                         + "<participant_2>" 
                         + "<domain_id>"
                         + WAN_DOMAIN_ID 
                         + "</domain_id>" 
                         + "<participant_qos>" 
                         + "<database>"
                         + "<cleanup_period>"
                         + "<sec>1</sec>"
                         + "</cleanup_period>"
                         + "</database>"
                         + "<transport_builtin><mask>MASK_NONE</mask></transport_builtin>" 
                         + "<property><value>" 
                         + "<element><name>dds.transport.load_plugins</name><value>dds.transport.TCPv4.tcp1</value></element>" 
                         + "<element><name>dds.transport.TCPv4.tcp1.library</name><value>nddstransporttcp</value></element>" 
                         + "<element><name>dds.transport.TCPv4.tcp1.create_function</name><value>NDDS_Transport_TCPv4_create</value></element>" 
                         + "<element><name>dds.transport.TCPv4.tcp1.parent.classid</name><value>NDDS_TRANSPORT_CLASSID_TCPV4_WAN</value></element>" 
                         + "<element><name>dds.transport.TCPv4.tcp1.public_address</name><value>" 
                         + ebAddress + ":" + EB_P2_PUB_BIND_PORT 
                         + "</value></element>" 
                         + "<element><name>dds.transport.TCPv4.tcp1.server_bind_port</name><value>" 
                         + EB_P2_PUB_BIND_PORT 
                         + "</value></element>"
                         + "</value></property>" 
                         + "</participant_qos>" 
                         + "</participant_2>" 
                         + "</domain_route>\"");
    }
    private void createSubDomainRoute(){
    	logger.debug(String.format("EB:%s will create a DomainRoute:%s between subscriber's local domain id:%d and wan domain id:%d\n",
    			ebAddress,subDomainRouteName,SUB_DOMAIN_ID,WAN_DOMAIN_ID));
    	
    	rs.createDomainRoute("str://\"<domain_route name=\"" + subDomainRouteName + "\">" 
                         + "<entity_monitoring>" 
                         + "<historical_statistics><up_time>true</up_time></historical_statistics>" 
                         + "</entity_monitoring>" 
                         + "<participant_1>" 
                         + "<domain_id>" + SUB_DOMAIN_ID+ "</domain_id>" 
                         + "<participant_qos>" 
                         + "<database>"
                         + "<cleanup_period>"
                         + "<sec>1</sec>"
                         + "</cleanup_period>"
                         + "</database>"
                         + "<transport_builtin><mask>UDPv4</mask></transport_builtin>" 
                         + "</participant_qos>" 
                         + "</participant_1>" 
                         + "<participant_2>" 
                         + "<domain_id>"
                         + WAN_DOMAIN_ID 
                         + "</domain_id>" 
                         + "<participant_qos>" 
                         + "<database>"
                         + "<cleanup_period>"
                         + "<sec>1</sec>"
                         + "</cleanup_period>"
                         + "</database>"
                         + "<transport_builtin><mask>MASK_NONE</mask></transport_builtin>" 
                         + "<property><value>" 
                         + "<element><name>dds.transport.load_plugins</name><value>dds.transport.TCPv4.tcp1</value></element>" 
                         + "<element><name>dds.transport.TCPv4.tcp1.library</name><value>nddstransporttcp</value></element>" 
                         + "<element><name>dds.transport.TCPv4.tcp1.create_function</name><value>NDDS_Transport_TCPv4_create</value></element>" 
                         + "<element><name>dds.transport.TCPv4.tcp1.parent.classid</name><value>NDDS_TRANSPORT_CLASSID_TCPV4_WAN</value></element>" 
                         + "<element><name>dds.transport.TCPv4.tcp1.public_address</name><value>" 
                         + ebAddress + ":" + EB_P2_SUB_BIND_PORT 
                         + "</value></element>" 
                         + "<element><name>dds.transport.TCPv4.tcp1.server_bind_port</name><value>" 
                         + EB_P2_SUB_BIND_PORT 
                         + "</value></element>" 
                         + "</value></property>" 
                         + "</participant_qos>" 
                         + "</participant_2>" 
                         + "</domain_route>\"");
    }

}
