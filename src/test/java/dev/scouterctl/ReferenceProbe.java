package dev.scouterctl;

import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import scouter.io.DataInputX;
import scouter.io.DataOutputX;
import scouter.lang.pack.*;
import scouter.lang.step.Step;
import scouter.net.NetCafe;
import scouter.net.RequestCmd;
import scouter.net.TcpFlag;
import scouter.util.CipherUtil;

/** Independent minimal Java wire reference, used only by the real-Collector smoke. */
public class ReferenceProbe {
    public static void main(String[] args) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(args[0]))) {
            socket.setSoTimeout(5000);
            DataInputX in = new DataInputX(new java.io.BufferedInputStream(socket.getInputStream()));
            DataOutputX out = new DataOutputX(new java.io.BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(NetCafe.TCP_CLIENT);
            MapPack login = new MapPack();
            login.put("id", "poc"); login.put("pass", CipherUtil.sha256(System.getenv("SCOUTERCTL_PASSWORD")));
            login.put("ip", "127.0.0.1"); login.put("hostname", "java-reference"); login.put("version", "poc");
            long session = ((MapPack) request(out, in, RequestCmd.LOGIN, 0, login).get(0)).getLong("session");
            if (session == 0) throw new IllegalStateException("Authentication failed");
            ObjectPack object = (ObjectPack) request(out, in, RequestCmd.OBJECT_LIST_REAL_TIME, session, null).get(0);
            System.out.println("objects=" + object.objHash);
            MapPack query = new MapPack();
            query.put("date", args[1]); query.put("stime", Long.parseLong(args[2])); query.put("etime", Long.parseLong(args[3]) - 1);
            query.put("pageCount", 10L); query.newList("objHash").add(42L);
            List<Pack> page = request(out, in, RequestCmd.TRANX_LOAD_TIME_GROUP_V2, session, query);
            List<String> ids = new ArrayList<>();
            for (Pack pack : page) if (pack instanceof XLogPack) ids.add(Long.toString(((XLogPack) pack).txid));
            System.out.println("txids=" + String.join(",", ids));
            MapPack profile = new MapPack();
            profile.put("date", args[1]); profile.put("txid", Long.parseLong(ids.get(0))); profile.put("max", 1000L);
            XLogProfilePack result = (XLogProfilePack) request(out, in, RequestCmd.TRANX_PROFILE, session, profile).get(0);
            System.out.println("steps=" + Step.toObjects(result.profile).length);
        }
    }
    private static List<Pack> request(DataOutputX out, DataInputX in, String command, long session, MapPack pack) throws Exception {
        out.writeText(command); out.writeLong(session);
        if (pack != null) out.writePack(pack);
        out.flush();
        List<Pack> result = new ArrayList<>();
        byte flag;
        while ((flag = in.readByte()) == TcpFlag.HasNEXT) result.add(in.readPack());
        if (flag != TcpFlag.NoNEXT) throw new IllegalStateException("Unexpected flag");
        return result;
    }
}
