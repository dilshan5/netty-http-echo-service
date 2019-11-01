# netty-http-echo-service
HTTP Echo Service developed using [Netty](https://netty.io/wiki/user-guide-for-4.x.html). A very useful debugging and measurement tool is an echo service. An echo service simply sends back to the originating source any data it receives. 



#Performance Test with Jmeter.
This is useful for performance tests. This will give the response time for:
   * Client to backend server
   * Server processing time
   * Backend to Client
   
# How to use
* Install Jmeter in your machine.
* Start the Echo server
* Run the jmeter script in the 'jmeter_script' folder

Following is a sample screenshot of a request. Please note that the timestamp is in UTC.

![Screenshot](jmeter_script/Jmeterlog.png)

