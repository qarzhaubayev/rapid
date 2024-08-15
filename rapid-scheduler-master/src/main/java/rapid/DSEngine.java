package rapid;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.project.rapid.common.RapidMessages;
import org.apache.log4j.Logger;
import java.io.*;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.io.IOException;

public class DSEngine {
    private static DSEngine dsEngine = new DSEngine();
    private Logger logger = Logger.getLogger(getClass());
    private final static Map<Long, Float> allocatedCpu = Collections.synchronizedMap(new HashMap<Long, Float>());
    private static final String apiUrl = "http://dqn:8000/predict";


    private DSEngine() { }

    public static DSEngine getInstance() {
        return dsEngine;
    }

    public void vmmRegisterDs(ObjectInputStream in, ObjectOutputStream out) {
        try {
            MainScheduler scheduler = MainScheduler.getInstance();

            String ipv4 = in.readUTF();
            int mactype = in.readInt();
            String macAddress = in.readUTF();
            int cpuload = in.readInt();
            int allocatedcpu = in.readInt();
            int cpufrequency = in.readInt();
            int cpunums = in.readInt();
            long freemem = in.readLong();
            long availmem = in.readLong();
            int powerusage = in.readInt();
            int freegpu = in.readInt();
            int gpunums = in.readInt();
            String availtypes = in.readUTF();

            VmmInfo vmmInfo = DSManager.getVmmInfoByIp(ipv4);

            if (vmmInfo != null) {
                out.writeByte(RapidMessages.ERROR); // errorCode
                out.flush();
                return;
            }

            vmmInfo = new VmmInfo();
            vmmInfo.setIpv4(ipv4);
            vmmInfo.setMactype(mactype);
            vmmInfo.setMacaddress(macAddress);
            vmmInfo.setCpuload(cpuload);
            vmmInfo.setAllocatedcpu(allocatedcpu);
            vmmInfo.setCpufrequency(cpufrequency);
            vmmInfo.setCpunums(cpunums);
            vmmInfo.setFreemem(freemem);
            vmmInfo.setAvailmem(availmem);
            vmmInfo.setPowerusage(powerusage);
            vmmInfo.setFreegpu(freegpu);
            vmmInfo.setGpunums(gpunums);
            vmmInfo.setAvailtypes(availtypes);

            long vmmId = DSManager.insertVmmInfo(vmmInfo);
            allocatedCpu.put(vmmId, (float) 0.0);

            logger.info("VMM_REGISTER_DS, returned SLAM IP: " + scheduler.getIpv4() + " SLAM Port: " + scheduler.getPort());

            out.writeByte(RapidMessages.OK); // errorCode
            out.writeLong(vmmId); // vmmId
            out.writeUTF(scheduler.getIpv4());
            out.writeInt(scheduler.getPort());
            out.flush();

        } catch (Exception e) {
            handleException(e);
        }
    }

    public void vmmNotifyDs(ObjectInputStream in, ObjectOutputStream out) {
        try {
            long vmmid = in.readLong();
            VmmInfo vmmInfo = DSManager.getVmmInfo(vmmid);
            VmmStats vmmStats = new VmmStats();
            logger.info("VMM_NOTIFY_DS VMM_ID = " + vmmid);
            if (vmmInfo != null) {
                int cpuload = in.readInt();
                vmmInfo.setCpuload(cpuload);
                int allocatedcpu = in.readInt();
                long freemem = in.readLong();
                vmmInfo.setFreemem(freemem);
                long availmem = in.readLong();
                vmmInfo.setAvailmem(availmem);
                int powerusage = in.readInt();
                vmmInfo.setPowerusage(powerusage);
                vmmInfo.setFreegpu(in.readInt());

                if (vmmInfo.getSuspended() == 1 && DSManager.wolLast6Sec(vmmInfo) == 0) {
                    vmmInfo.setSuspended(0);

                    WolHistory wolHistory = new WolHistory();
                    wolHistory.setVmmid(vmmInfo.getVmmid());
                    wolHistory.setIswol(1);
                    DSManager.insertWolHistory(wolHistory);
                }

                DSManager.updateVmmInfo(vmmInfo);

                vmmStats.setVmmid(vmmid);
                vmmStats.setCpuload(cpuload);
                vmmStats.setAllocatedcpu(vmmInfo.getAllocatedcpu());
                vmmStats.setFreemem(freemem);
                vmmStats.setAvailmem(availmem);
                vmmStats.setPowerusage(powerusage);

                DSManager.insertVmmStats(vmmStats);
            }

        } catch (Exception e) {
            handleException(e);
        }
    }

    private synchronized void incrementAllocatedcpu(VmmConfig vmmConfig) {
        VmmInfo vmmInfo = DSManager.getVmmInfoByIp(vmmConfig.getVmmIP());
        float allocatedcpuDelta = (float) vmmConfig.getVcpu() / vmmInfo.getCpunums();
        allocatedCpu.put(vmmInfo.getVmmid(), allocatedCpu.get(vmmInfo.getVmmid()) + allocatedcpuDelta);
        vmmInfo.setAllocatedcpu(allocatedCpu.get(vmmInfo.getVmmid()));
        DSManager.updateVmmInfo(vmmInfo);
    }

    private synchronized void decrementAllocatedcpu(String vmmIp, long userID) {
        VmmInfo vmmInfo = DSManager.getVmmInfoByIp(vmmIp);
        UserInfo userInfo = DSManager.getUserInfo(userID);
        float allocatedcpuDelta = - (float) userInfo.getVcpu() / vmmInfo.getCpunums();
        allocatedCpu.put(vmmInfo.getVmmid(), allocatedCpu.get(vmmInfo.getVmmid()) + allocatedcpuDelta);
        vmmInfo.setAllocatedcpu(allocatedCpu.get(vmmInfo.getVmmid()));
        DSManager.updateVmmInfo(vmmInfo);
    }

    public void acRegisterNewDs(ObjectInputStream in, ObjectOutputStream out, Socket socket) {
        try {
            long userid = in.readLong();
            int vcpuNum = in.readInt();
            int memSize = in.readInt();
            int gpuCores = in.readInt();
            String deadline = in.readUTF();
            long cycles = in.readLong();

            logger.info("AC_REGISTER_NEW_DS, userId: " + userid + " vcpuNum: " + vcpuNum + " memSize: " + memSize
                    + " gpuCores: " + gpuCores + " deadline: " + deadline + " cycles: " + cycles);

            VmmConfig vmmConfig = dsEngine.findAvailMachines(userid, vcpuNum, memSize, gpuCores, deadline, cycles);
            ArrayList<String> ipList = new ArrayList<>();
            if (vmmConfig != null) {
                ipList.add(vmmConfig.getVmmIP());
                incrementAllocatedcpu(vmmConfig);
            }

            if (userid > 0) {
                VmInfo vmInfo = DSManager.getVmInfoByUserid(userid);
                UserInfo userInfo = DSManager.getUserInfo(userid);
                userInfo.setDeadline(deadline);
                userInfo.setCycles(cycles);
                userInfo.setVcpu(vmmConfig.getVcpu());
                userInfo.setMemory(vmmConfig.getMemory());
                DSManager.updateUserInfo(userInfo);

                if (vmInfo != null && vmInfo.getOffloadstatus() != VmInfo.OFFLOAD_DEREGISTERED) {
                    vmInfo.setOffloadstatus(VmInfo.OFFLOAD_DEREGISTERED);
                    vmInfo.setVmstatus(VmInfo.VM_STOPPED);
                    DSManager.updateVmInfo(vmInfo);

                    MainScheduler scheduler = MainScheduler.getInstance();
                    VmmInfo vmmInfo = DSManager.getVmmInfo(vmInfo.getVmmid());

                    Socket vmmSocket = new Socket(vmmInfo.getIpv4(), scheduler.getVmmPort());
                    ObjectOutputStream vmmOut = new ObjectOutputStream(vmmSocket.getOutputStream());
                    vmmOut.flush();

                    vmmOut.writeByte(RapidMessages.DS_VM_DEREGISTER_VMM);
                    vmmOut.writeLong(userid);
                    vmmOut.flush();

                    vmmOut.close();
                    vmmSocket.close();
                }
            }

            if (ipList.size() == 0) {
                out.writeByte(RapidMessages.ERROR);
                out.flush();
                return;
            }

            long newUserid = userid;
            if (userid < 0) {
                UserInfo userInfo = new UserInfo();
                InetSocketAddress addr = (InetSocketAddress) socket.getRemoteSocketAddress();
                userInfo.setIpv4(getIpAddress(addr.getAddress().getAddress()));
                userInfo.setDeadline(deadline);
                userInfo.setCycles(cycles);
                userInfo.setVcpu(vmmConfig.getVcpu());
                userInfo.setMemory(vmmConfig.getMemory());
                newUserid = DSManager.insertUserInfo(userInfo);
            }

            out.writeByte(RapidMessages.OK);
            out.writeLong(newUserid);
            out.writeObject(ipList);
     
            out.flush();

        } catch (Exception e) {
            handleException(e);
        }
    }

    public void acRegisterPrevDs(ObjectInputStream in, ObjectOutputStream out, Socket socket) {
        try {
            long userid = in.readLong();
            String deadline = in.readUTF();
            long cycles = in.readLong();

            logger.info("AC_REGISTER_PREV_DS, userId: " + userid);

            UserInfo userInfo = DSManager.getUserInfo(userid);
            if (userInfo == null) {
                out.writeByte(RapidMessages.ERROR);
                out.flush();
                return;
            }

            userInfo.setDeadline(deadline);
            userInfo.setCycles(cycles);
            VmInfo vmInfo = DSManager.getVmInfoByUserid(userid);
            if (vmInfo == null || vmInfo.getOffloadstatus() == VmInfo.OFFLOAD_DEREGISTERED) {
                out.writeByte(RapidMessages.ERROR);
                out.flush();
                return;
            }

            MainScheduler scheduler = MainScheduler.getInstance();
            VmmInfo vmmInfo = DSManager.getVmmInfo(vmInfo.getVmmid());
            if (vmmInfo == null) {
                out.writeByte(RapidMessages.ERROR);
                out.flush();
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) socket.getRemoteSocketAddress();
            userInfo.setIpv4(getIpAddress(addr.getAddress().getAddress()));
            DSManager.updateUserInfo(userInfo);

            ArrayList<String> ipList = new ArrayList<>();
            ipList.add(vmmInfo.getIpv4());

            out.writeByte(RapidMessages.OK);
            out.writeLong(userid);
            out.writeObject(ipList);
            addr = (InetSocketAddress) socket.getLocalSocketAddress();
            out.writeUTF(getIpAddress(addr.getAddress().getAddress()));
            out.writeInt(scheduler.getPort());
            out.flush();

        } catch (Exception e) {
            handleException(e);
        }
    }

    public void vmRegisterDs(ObjectInputStream in, ObjectOutputStream out) {
        try {
            long vmmid = in.readLong();
            int category = in.readInt();
            int type = in.readInt();
            long userid = in.readLong();
            int vmstatus = in.readInt();
            int vcpu = in.readInt();
            long memory = in.readInt();

            String ipv4 = in.readUTF();
            int port = in.readInt();

            logger.info("VM_REGISTER_DS, vmmid: " + vmmid + " type: " + type + " vmstatus: " + VmInfo.vmStatus.get(vmstatus));

            VmInfo vmInfo = new VmInfo();
            vmInfo.setVmmid(vmmid);
            vmInfo.setCategory(category);
            vmInfo.setType(type);
            vmInfo.setUserid(userid);
            vmInfo.setVmstatus(vmstatus);
            vmInfo.setVcpu(vcpu);
            vmInfo.setMemory(memory);
            vmInfo.setIpv4(ipv4);
            vmInfo.setPort(port);

            long newVmid = DSManager.insertVmInfo(vmInfo);
            out.writeLong(newVmid);
            out.flush();
        } catch (Exception e) {
            handleException(e);
        }
    }

    public void vmNotifyDs(ObjectInputStream in, ObjectOutputStream out) {
        try {
            long vmid = in.readLong();
            int vmstatus = in.readInt();
            int offloadstatus = in.readInt();

            VmInfo vmInfo = DSManager.getVmInfo(vmid);
            logger.info("VM_NOTIFY_DS, vmid: " + vmid + ", vmstatus:" + VmInfo.vmStatus.get(vmstatus) + "offloadstatus:" + VmInfo.offloadstatusMap.get(offloadstatus));

            if (vmInfo != null) {
                vmInfo.setVmstatus(vmstatus);
                vmInfo.setOffloadstatus(offloadstatus);
                DSManager.updateVmInfo(vmInfo);

                if (offloadstatus == VmInfo.OFFLOAD_DEREGISTERED) {
                    OffloadHistory offloadHistory = DSManager.getOffloadHistoryByuservmmid(vmInfo.getVmmid(), vmInfo.getUserid());
                    DSManager.updateOffloadHistory(offloadHistory);
                    decrementAllocatedcpu(vmInfo.getIpv4(), vmInfo.getUserid());
                }
            }
        } catch (Exception e) {
            handleException(e);
        }
    }

    public void helperNotifyDs(ObjectInputStream in, ObjectOutputStream out) {
        try {
            long vmid = in.readLong();
            String ipv4 = in.readUTF();

            VmInfo vmInfo = DSManager.getVmInfo(vmid);
            if (vmInfo != null) {
                vmInfo.setIpv4(ipv4);
                logger.info("helperNotifyDs: ipAddress:" + ipv4);
                DSManager.updateVmInfo(vmInfo);
            }
        } catch (Exception e) {
            handleException(e);
        }
    }

    public void asRmRegisterDs(ObjectInputStream in, ObjectOutputStream out, Socket socket) {
        try {
            long userid = in.readLong();
            VmInfo vmInfo = DSManager.getVmInfoByUserid(userid);

            logger.info("AS_RM_REGISTER_DS, userid: " + userid);

            if (vmInfo == null) {
                out.writeByte(RapidMessages.ERROR);
                out.flush();
                return;
            }

            logger.info("AS_RM_REGISTER_DS, VM found: vmid: " + vmInfo.getVmid());

            InetSocketAddress addr = (InetSocketAddress) socket.getRemoteSocketAddress();
            vmInfo.setOffloadstatus(VmInfo.OFFLOAD_REGISTERED);
            DSManager.updateVmInfo(vmInfo);

            out.writeByte(RapidMessages.OK);
            out.writeLong(vmInfo.getVmid());
            out.flush();
        } catch (Exception e) {
            handleException(e);
        }
    }

    private String getIpAddress(byte[] rawBytes) {
        int i = 4;
        String ipAddress = "";
        for (byte raw : rawBytes) {
            ipAddress += (raw & 0xFF);
            if (--i > 0) {
                ipAddress += ".";
            }
        }
        return ipAddress;
    }

	private static String sendPostRequest(String jsonInputString) {
    HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)) // Set connection timeout
            .build();

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl)) // Use the apiUrl field for the URI
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonInputString))
            .build();

    try {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body(); // Return the response body as a String
    } catch (IOException e) {
        return null; // Or handle this error in another appropriate way
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); // Preserve interrupt status
        return null;
    }
}

    public long getNodeDecision(List<Long> vmmid, List<Integer> cpuload, List<Float> allocatedcpu, List<Long> freemem, List<Long> availmem, List<Integer> powerusage) {
        try {
            Map<String, List<Float>> stats = new HashMap<>();
            stats.put("vmmid", vmmid.stream().map(Long::floatValue).collect(Collectors.toList()));
            stats.put("cpuload", cpuload.stream().map(Integer::floatValue).collect(Collectors.toList()));
            stats.put("allocatedcpu", allocatedcpu);
            stats.put("freemem", freemem.stream().map(Long::floatValue).collect(Collectors.toList()));
            stats.put("availmem", availmem.stream().map(Long::floatValue).collect(Collectors.toList()));
            stats.put("powerusage", powerusage.stream().map(Integer::floatValue).collect(Collectors.toList()));

            String jsonInputString = new ObjectMapper().writeValueAsString(stats);
	    
         
            String response = sendPostRequest(jsonInputString);
            System.out.println("Response from server: " + response);
	    ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> responseMap = mapper.readValue(response, Map.class);

            long action = ((Number) responseMap.get("action")).longValue();
            return action;

        } catch (IOException e) {
            logger.error("Error in getNodeDecision: ", e);
            return -1;
        }
    }

    private synchronized VmmConfig findAvailMachines(long userid, int vcpuNum, int memSize, int gpuCores, String deadline, long cycles) throws IOException, InterruptedException {
        List<VmmInfo> vmmInfoList = DSManager.vmmInfoListByHighAllocatedCpu();
        boolean allSuspended = true;
	int minvcpu = 40;
        List<Long> vmmid = new ArrayList<>();
        List<Integer> cpuload = new ArrayList<>();
        List<Float> allocatedcpu = new ArrayList<>();
        List<Long> freemem = new ArrayList<>();
        List<Long> availmem = new ArrayList<>();
        List<Integer> powerusage = new ArrayList<>();

        for (VmmInfo vmmInfo : vmmInfoList) {
            if (vmmInfo.getSuspended() == 0) {
                long millionCycles = cycles / 1000000;
                logger.info("CURRENT ALLOC CPU: " + vmmInfo.getAllocatedcpu());
                int minExecTime = (int) Math.ceil((double) millionCycles / (vmmInfo.getCpufrequency() * Math.min((double) vmmInfo.getCpunums() * ((100 - vmmInfo.getAllocatedcpu()) / 100.0), 1.0)));
                logger.info("DECIDING vmmid: " + vmmInfo.getVmmid());
                logger.info("minExecTime: " + minExecTime);

                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                LocalDateTime deadlineLDT = LocalDateTime.parse(deadline, formatter);
                long allowedTime = ChronoUnit.SECONDS.between(LocalDateTime.now(), deadlineLDT);
                logger.info("allowedTime: " + allowedTime);

                if (minExecTime < 1 || minExecTime > allowedTime || vmmInfo.getAllocatedcpu() > (100 - (double) minvcpu / vmmInfo.getCpunums())) {
                    logger.info("machine vmmid=" + vmmInfo.getVmmid() + " is NOT suitable!");
                    continue;
                }

                vmmid.add(vmmInfo.getVmmid());
                cpuload.add(vmmInfo.getCpuload());
                allocatedcpu.add(vmmInfo.getAllocatedcpu());
                freemem.add(vmmInfo.getFreemem());
                availmem.add(vmmInfo.getAvailmem());
                powerusage.add(vmmInfo.getPowerusage());
                allSuspended = false;
            }
        }

        long chosen_vmmid = getNodeDecision(vmmid, cpuload, allocatedcpu, freemem, availmem, powerusage);
        VmmInfo vmmInfo = vmmInfoList.stream().filter(info -> info.getVmmid() == chosen_vmmid).findFirst().orElse(null);

        if (vmmInfo != null && !allSuspended) {
            int selectedVcpu = vcpuNum;
            String selectedVmmIp = vmmInfo.getIpv4();
            int selectedMemory = memSize;

            RequestInfo requestInfo = new RequestInfo();
            requestInfo.setAccepted(1);
            requestInfo.setVmmid(vmmInfo.getVmmid());
            requestInfo.setUserid(userid);
            requestInfo.setDeadline(deadline);
            requestInfo.setVcpu(selectedVcpu);
            requestInfo.setMemory(selectedMemory);
            requestInfo.setCycles(cycles);
            DSManager.insertRequestInfo(requestInfo);

            return new VmmConfig(selectedVmmIp, selectedVcpu, selectedMemory);
        }

        RequestInfo requestInfo = new RequestInfo();
        requestInfo.setAccepted(0);
        requestInfo.setUserid(userid);
        requestInfo.setDeadline(deadline);
        requestInfo.setCycles(cycles);
        DSManager.insertRequestInfo(requestInfo);

        return null;
    }

    public void suspendVmm(long vmmid) {
        try {
            VmmInfo vmmInfo = DSManager.getVmmInfo(vmmid);
            if (vmmInfo == null) {
                logger.info("VmmInfo not found when trying to suspend a VMM");
            } else {
                vmmInfo.setSuspended(1);
                DSManager.updateVmmInfo(vmmInfo);

                WolHistory wolHistory = new WolHistory();
                wolHistory.setVmmid(vmmInfo.getVmmid());
                wolHistory.setIswol(0);
                DSManager.insertWolHistory(wolHistory);

                MainScheduler scheduler = MainScheduler.getInstance();
                Socket vmmSocket = new Socket(vmmInfo.getIpv4(), scheduler.getVmmPort());
                ObjectOutputStream vmmOut = new ObjectOutputStream(vmmSocket.getOutputStream());
                vmmOut.flush();

                vmmOut.writeByte(RapidMessages.DS_VMM_SUSPEND);
                vmmOut.flush();

                vmmOut.close();
                vmmSocket.close();
            }
        } catch (Exception e) {
            logger.error("Exception", e);
        }
    }

    public static void wakeOnLan(String ipStr, String macStr) {
        final int PORT = 9;
        try {
            byte[] macBytes = getMacBytes(macStr);
            byte[] bytes = new byte[6 + 16 * macBytes.length];
            for (int i = 0; i < 6; i++) {
                bytes[i] = (byte) 0xff;
            }
            for (int i = 6; i < bytes.length; i += macBytes.length) {
                System.arraycopy(macBytes, 0, bytes, i, macBytes.length);
            }

            InetAddress address = InetAddress.getByName(ipStr);
            DatagramPacket packet = new DatagramPacket(bytes, bytes.length, address, PORT);
            DatagramSocket socket = new DatagramSocket();
            socket.send(packet);
            socket.close();

        } catch (Exception e) {
            System.out.println("Failed to send Wake-on-LAN packet: + e");
        }
    }

    private static byte[] getMacBytes(String macStr) throws IllegalArgumentException {
        byte[] bytes = new byte[6];
        String[] hex = macStr.split("(\\:|\\-)");
        if (hex.length != 6) {
            throw new IllegalArgumentException("Invalid MAC address.");
        }
        try {
            for (int i = 0; i < 6; i++) {
                bytes[i] = (byte) Integer.parseInt(hex[i], 16);
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid hex digit in MAC address.");
        }
        return bytes;
    }

    public static String runWithPrivileges(String cmd) throws IOException {
        String[] cmds = { "/bin/sh", "-c", cmd };
        java.util.Scanner s = new java.util.Scanner(Runtime.getRuntime().exec(cmds).getInputStream()).useDelimiter("\\n");
        return s.hasNext() ? s.next() : "";
    }

    public void vmmRegisterSlam(ObjectInputStream in, ObjectOutputStream out) throws IOException {
        long threadId = Thread.currentThread().getId();
        try {
            logger.debug("VMM_REGISTER_SLAM() start" + "Thread Id: " + threadId);
            String vmmIP = in.readUTF();
            int vmmPort = in.readInt();
            logger.debug("A vmm has been registered with ip: " + vmmIP + " port: " + vmmPort);
            out.writeByte(RapidMessages.OK);
        } catch (Exception e) {
            logger.error("Exception", e);
            out.writeByte(RapidMessages.ERROR);
        }
        logger.debug("Thread Id: " + threadId + " | VMM_REGISTER_SLAM() end");
        out.flush();
    }

    public void acRegisterSlam(ObjectInputStream in, long threadId, ObjectOutputStream out) throws IOException {
        logger.debug("Thread Id: " + threadId + " | AC_REGISTER_SLAM()");
        long userID = 0;
        try {
            userID = in.readLong();
            int osType = in.readInt();
            String vmmIP = in.readUTF();
            int vmmPort = in.readInt();
            int vcpuNum = in.readInt();
            int memSize = in.readInt();
            UserInfo userInfo = DSManager.getUserInfo(userID);
            vcpuNum = userInfo.getVcpu();
            memSize = userInfo.getMemory();
            int gpuCores = in.readInt();
            String qosinjson = in.readUTF();

            logger.debug("[Flow-" + userID + "] ************ PARAMETERS RECEIVED ************");
            logger.debug("[Flow-" + userID + "] userID:[" + userID + "], osType:[" + osType + "], vmmIP:[" + vmmIP + "], vmmPort:[" + vmmPort + "], vcpuNum:[" + vcpuNum + "], memSize:[" + memSize + "], gpuCores:[" + gpuCores + "], qosinjson:[" + qosinjson + "]");
            logger.debug("[Flow-" + userID + "] ********************************************");

            VmInfo vmInfo = slamStartVmVmm(userID, osType, vmmIP, vmmPort, vcpuNum, memSize, gpuCores);
            String vmIp = vmInfo.getIpv4();
            int vmPort = vmInfo.getPort();

            if (!"".equals(vmIp)) {
                out.writeByte(RapidMessages.OK);
                out.writeUTF(vmIp);
                out.writeInt(vmPort);
            } else {
                out.writeByte(RapidMessages.ERROR);
            }

            logger.debug("[Flow] User id: " + userID + "vmmPort: " + vmmPort + " osType: " + osType + " vmmIP: " + vmmIP + " vcpuNum: " + vcpuNum + " memSize: " + memSize + " gpuCores: " + gpuCores + " vmIp: " + vmIp + " vmPort: " + vmPort);
            logger.debug("[Flow] Thread Id: " + threadId + " | Finished processing AC_REGISTER_SLAM()");
        } catch (Exception e) {
            logger.error("[Flow-" + userID + "] VMM not respond. socket worker runnable Exception", e);
        }
        out.flush();
    }

    private VmInfo slamStartVmVmm(long userID, int osType, String vmmIp, int vmmPort, int vcpuNum, int memSize, int gpuCores) throws IOException {
        logger.debug("slamStartVmVmm() start userID: " + userID + " osType: " + osType);
        logger.debug("[Flow-" + userID + "] Calling VMM manager socket server running at: " + vmmIp + ":" + vmmPort);

        String ip = null;
        int port = -1;

        Socket vmmSocket = new Socket(vmmIp, vmmPort);
        vmmSocket.setSoTimeout(120000);
        ObjectOutputStream vmmOut = new ObjectOutputStream(vmmSocket.getOutputStream());
        vmmOut.flush();
        ObjectInputStream vmmIn = new ObjectInputStream(vmmSocket.getInputStream());

        logger.debug("[Flow-" + userID + "] RapidMessages.SLAM_START_VM_VMM params: userID=" + userID + ", osType=" + osType + ", vcpuNum=" + vcpuNum + ", memSize=" + memSize + ", gpuCores=" + gpuCores);
        vmmOut.writeByte(RapidMessages.SLAM_START_VM_VMM);
        vmmOut.writeLong(userID);
        vmmOut.writeInt(osType);
        vmmOut.writeInt(vcpuNum);
        vmmOut.writeInt(memSize);
        vmmOut.writeInt(gpuCores);
        vmmOut.flush();

        byte status = vmmIn.readByte();
        logger.debug("[Flow-" + userID + "] RapidMessages.SLAM_START_VM_VMM status: " + status);
        logger.debug("Return Status: " + (status == RapidMessages.OK ? "OK" : "ERROR"));

        if (status == RapidMessages.OK) {
            long user_id = vmmIn.readLong();
            ip = vmmIn.readUTF();
            port = vmmIn.readInt();
            logger.debug("Successfully retrieved VM ip: " + ip);

            OffloadHistory offloadHistory = new OffloadHistory();
            long vmmid = DSManager.getVmmInfoByIp(vmmIp).getVmmid();
            offloadHistory.setVmmid(vmmid);
            offloadHistory.setUserid(userID);
            offloadHistory.setVcpu(vcpuNum);
            offloadHistory.setMemory(memSize);
            UserInfo userInfo = DSManager.getUserInfo(user_id);
            offloadHistory.setDeadline(userInfo.getDeadline());
            offloadHistory.setCycles(userInfo.getCycles());
            DSManager.insertOffloadHistory(offloadHistory);

            VmmInfo vmmInfo = DSManager.getVmmInfo(vmmid);
            int cpuLoad = 100 / vmmInfo.getCpunums();
            vmmInfo.setCpuload(vmmInfo.getCpuload() + cpuLoad);
            DSManager.updateVmmInfo(vmmInfo);
        } else {
            logger.error("Error! returning null..");
            decrementAllocatedcpu(vmmIp, userID);
            ip = "";
        }
        logger.debug("[Flow-" + userID + "] RapidMessages.SLAM_START_VM_VMM ip: " + ip);

        vmmOut.close();
        vmmIn.close();
        vmmSocket.close();
        logger.debug("SlamStartVmVmm() end");

        VmInfo vmInfo = new VmInfo();
        vmInfo.setIpv4(ip);
        vmInfo.setPort(port);
        return vmInfo;
    }

    private void handleException(Exception e) {
        String message = "";
        for (StackTraceElement stackTraceElement : Thread.currentThread().getStackTrace()) {
            message = message + System.lineSeparator() + stackTraceElement.toString();
        }
        logger.error("Caught Exception: " + e.getMessage() + System.lineSeparator() + message);
        e.printStackTrace();
    }
}

class VmmConfig {
    private String vmmIP;
    private int vcpu;
    private int memory;

    public VmmConfig(String vmmIP, int vcpu, int memory) {
        this.vmmIP = vmmIP;
        this.vcpu = vcpu;
        this.memory = memory;
    }

    public int getVcpu() {
        return vcpu;
    }

    public void setVcpu(int vcpu) {
        this.vcpu = vcpu;
    }

    public int getMemory() {
        return memory;
    }

    public void setMemory(int memory) {
        this.memory = memory;
    }

    public String getVmmIP() {
        return vmmIP;
    }

    public void setVmmIP(String vmmIP) {
        this.vmmIP = vmmIP;
    }
}

