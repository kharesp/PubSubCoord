package edu.vanderbilt.kharesp.pubsubcoord.brokers;

import java.net.InetAddress;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.CloseableUtils;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import com.rti.dds.domain.DomainParticipant;
import com.rti.dds.domain.DomainParticipantFactory;
import com.rti.dds.domain.DomainParticipantFactoryQos;
import com.rti.dds.domain.builtin.ParticipantBuiltinTopicDataDataReader;
import com.rti.dds.domain.builtin.ParticipantBuiltinTopicDataTypeSupport;
import com.rti.dds.infrastructure.StatusKind;
import com.rti.dds.publication.builtin.PublicationBuiltinTopicDataDataReader;
import com.rti.dds.publication.builtin.PublicationBuiltinTopicDataTypeSupport;
import com.rti.dds.subscription.Subscriber;
import com.rti.dds.subscription.builtin.SubscriptionBuiltinTopicDataDataReader;
import com.rti.dds.subscription.builtin.SubscriptionBuiltinTopicDataTypeSupport;
import com.rti.idl.RTI.RoutingService.Administration.CommandKind;

public class EdgeBroker {
	// Domain id in which Routing Brokers operate in the cloud
	private static final int WAN_DOMAIN_ID = 230;
    // public facing port for sending/receiving data for this local domain
    private static final String EB_P2_BIND_PORT = "8502";
    private static final String DOMAIN_ROUTE_NAME_PREFIX = "EdgeBrokerDomainRoute";

    private String ebAddress;
    private String ebLocator;
    private String domainRouteName; 
    private String zkConnector;
    private int lanDomainId;
    private RoutingServiceAdministrator rs;
    private CuratorFramework client = null;
    private DomainParticipant builtinTopicsParticipant = null;
    private Logger logger;

    public EdgeBroker(String zkConnector,int lanDomainId){
    	this.zkConnector=zkConnector;
    	this.lanDomainId=lanDomainId;
    	logger=Logger.getLogger(this.getClass().getSimpleName());

        try {
             ebAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (java.net.UnknownHostException e) {
            System.out.println("Host address is not known");
        }
        ebLocator = ebAddress + ":" + EB_P2_BIND_PORT;

        // Create Routing Service remote administrator 
        try {
			rs=new RoutingServiceAdministrator(ebAddress);
		} catch (Exception e) {
            logger.error(e.getMessage(),e);
		}
    }
    public static void main(String args[]){
    	if (args.length<2){
    		System.out.println("Enter zkConnector (address:port) and domainID for lan");
    		return;
    	}
    	String zkConnector=args[0];
    	int lanDomainId=Integer.parseInt(args[1]);
    	PropertyConfigurator.configure("log4j.properties");
    	new EdgeBroker(zkConnector,lanDomainId).start();
    }
    
    public void start(){
    	logger.debug(String.format("Starting EdgeBroker:%s\n",ebAddress));
    	// Create a domain route between local domain and wan domain in which RBs operate 
    	domainRouteName=DOMAIN_ROUTE_NAME_PREFIX + "@"+ ebAddress;
    	createDomainRoute();
    	
    	// Connect to ZK
    	client = CuratorFrameworkFactory.newClient(zkConnector,
                new ExponentialBackoffRetry(1000, 3));
        client.start();
        
        try{
        	// Ensure /topics path exists 
        	try {
        		logger.debug(String.format("EdgeBroker:%s ensure zk path %s exists\n", ebAddress,CuratorHelper.TOPIC_PATH));
        		client.create().withMode(CreateMode.PERSISTENT).forPath(CuratorHelper.TOPIC_PATH, new byte[0]);
        	} catch (KeeperException.NodeExistsException e) {
        		System.out.println("/topics znode already exists");
        	}

        	// Create built-in entities
        	createBuiltinTopics();

        	while (true) {
        		Thread.sleep(1000);
        	}
        }catch(Exception e){
        	logger.error(e.getMessage(),e);    	
        }finally{
        	CloseableUtils.closeQuietly(client);	
        }
    }
    private void createBuiltinTopics() {
    	try{
    			logger.debug(String.format("EdgeBroker:%s installing listeners for builtin topics\n",ebAddress));
    			// By default, the participant is enabled on construction. 
    			//Disable participant until listeners for built-in topics get installed
				DomainParticipantFactoryQos factory_qos = new DomainParticipantFactoryQos();
				DomainParticipantFactory.TheParticipantFactory.get_qos(factory_qos);
				factory_qos.entity_factory.autoenable_created_entities = false;
				DomainParticipantFactory.TheParticipantFactory.set_qos(factory_qos);

				builtinTopicsParticipant = DomainParticipantFactory.TheParticipantFactory.create_participant(lanDomainId,
					DomainParticipantFactory.PARTICIPANT_QOS_DEFAULT, null, StatusKind.STATUS_MASK_NONE);
				if (builtinTopicsParticipant == null) {
					throw new Exception("Participant creation failed");
				}
				// obtain builtin topics subscriber to listen for participant,publisher and subscriber creation
				Subscriber builtin_subscriber = builtinTopicsParticipant.get_builtin_subscriber();
				if (builtin_subscriber == null) {
					throw new Exception("Subscriber creation failed");
				}
				// obtain DR to listen for participant creation
				ParticipantBuiltinTopicDataDataReader builtin_participant_datareader = (ParticipantBuiltinTopicDataDataReader) builtin_subscriber
						.lookup_datareader(ParticipantBuiltinTopicDataTypeSupport.PARTICIPANT_TOPIC_NAME);
				if (builtin_participant_datareader == null) {
					throw new Exception("Built-in Participant DataReader creation failed");
				}
				
				// obtain DR to listen for publisher creation
				PublicationBuiltinTopicDataDataReader builtin_publication_datareader = (PublicationBuiltinTopicDataDataReader) builtin_subscriber
						.lookup_datareader(PublicationBuiltinTopicDataTypeSupport.PUBLICATION_TOPIC_NAME);
				if (builtin_publication_datareader == null) {
					throw new Exception("Built-in Publication DataReader creation failed");
				}
				
				logger.debug(String.format("EdgeBroker:%s installing listener for publisher discovery\n",ebAddress));
				// Install listener for Publication discovery
				BuiltinPublisherListener builtin_publisher_listener = new BuiltinPublisherListener(ebAddress,client,rs);
				builtin_publication_datareader.set_listener(builtin_publisher_listener, StatusKind.STATUS_MASK_ALL);

				// obtian DR to listen for subscriber creation 
				SubscriptionBuiltinTopicDataDataReader builtin_subscription_datareader = (SubscriptionBuiltinTopicDataDataReader) builtin_subscriber
					.lookup_datareader(SubscriptionBuiltinTopicDataTypeSupport.SUBSCRIPTION_TOPIC_NAME);
				if (builtin_subscription_datareader == null) {
					throw new IllegalStateException("Built-in Subscription DataReader creation failed");
				}

				logger.debug(String.format("EdgeBroker:%s installing listener for subscriber discovery\n",ebAddress));
				// Install listener for Subscription discovery
				BuiltinSubscriberListener builtin_subscriber_listener = new BuiltinSubscriberListener(ebAddress,client,rs);
				builtin_subscription_datareader.set_listener(builtin_subscriber_listener, StatusKind.STATUS_MASK_ALL);

				// All the listeners are installed, so we can enable the participant
				builtinTopicsParticipant.enable();
    	}catch (Exception e){
    		if (builtinTopicsParticipant!= null) {
                builtinTopicsParticipant.delete_contained_entities();
                DomainParticipantFactory.TheParticipantFactory.
                        delete_participant(builtinTopicsParticipant);
            }
    	}
    }
    private void createDomainRoute(){
    	logger.debug(String.format("EB:%s will create a DomainRoute:%s between local domain id:%d and wan domain id:%d\n",
    			ebAddress,domainRouteName,lanDomainId,WAN_DOMAIN_ID));
    	
    	rs.sendRequest(CommandKind.RTI_ROUTING_SERVICE_COMMAND_CREATE,
    			"str://\"<domain_route name=\"" + domainRouteName + "\">" +
                         "<entity_monitoring>" +
                         "<historical_statistics><up_time>true</up_time></historical_statistics>" +
                         "</entity_monitoring>" +
                         "<participant_1>" +
                         "<domain_id>" + lanDomainId+ "</domain_id>" +
                         "</participant_1>" +
                         "<participant_2>" +
                         "<domain_id>" + WAN_DOMAIN_ID + "</domain_id>" +
                         "<participant_qos>" +
                         "<transport_builtin><mask>MASK_NONE</mask></transport_builtin>" +
                         "<property><value>" +
                         "<element><name>dds.transport.load_plugins</name><value>dds.transport.TCPv4.tcp1</value></element>" +
                         "<element><name>dds.transport.TCPv4.tcp1.library</name><value>nddstransporttcp</value></element>" +
                         "<element><name>dds.transport.TCPv4.tcp1.create_function</name><value>NDDS_Transport_TCPv4_create</value></element>" +
                         "<element><name>dds.transport.TCPv4.tcp1.parent.classid</name><value>NDDS_TRANSPORT_CLASSID_TCPV4_WAN</value></element>" +
                         "<element><name>dds.transport.TCPv4.tcp1.public_address</name><value>" +
                         ebLocator +
                         "</value></element>" +
                         "<element><name>dds.transport.TCPv4.tcp1.server_bind_port</name><value>" +
                         EB_P2_BIND_PORT +
                         "</value></element>" +
                         "</value></property>" +
                         "</participant_qos>" +
                         "</participant_2>" +
                         "</domain_route>\"");
    }
}