package com.focuspen.enhance.v2;

import java.io.*;
import java.nio.file.*;
import java.util.Arrays;

public final class WritingAudioLoopTest {
    public static void main(String[] args) throws Exception {
        byte[] wave = wav(44100, 44100);
        WritingAudioSample source = WritingAudioSample.read(new ByteArrayInputStream(wave));
        check(source.pcm.length == 44100 && source.sampleRate == 44100, "WAV decode");
        byte[] extended = new byte[wave.length + 24];
        System.arraycopy(wave, 0, extended, 0, 36);
        System.arraycopy(wave, 36, extended, 60, wave.length - 36);
        putInt(extended, 4, extended.length - 8); putInt(extended, 16, 40);
        extended[20]=(byte)254;extended[21]=(byte)255;extended[36]=22;extended[38]=16;
        putInt(extended,44,1);putInt(extended,48,0x00100000);
        putInt(extended,52,0xaa000080);putInt(extended,56,0x719b3800);
        check(Arrays.equals(source.pcm,WritingAudioSample.read(new ByteArrayInputStream(extended)).pcm),"extensible PCM matches plain WAV");
        extended[44]=3;reject(extended);
        double sum = 0;
        for (float v : source.pcm) { check(Float.isFinite(v) && Math.abs(v) <= .801f, "bounded PCM"); sum += v; }
        check(Math.abs(sum / source.pcm.length) < 1e-6, "DC removal");
        reject(new byte[40]);
        reject(Arrays.copyOf(wave, wave.length - 5));
        byte[] bad = wave.clone(); bad[34] = 8; reject(bad);
        bad = wave.clone(); Arrays.fill(bad, 44, bad.length, (byte) 0); reject(bad);
        bad = wave.clone(); bad[40] = (byte)255; bad[41] = (byte)255; bad[42] = (byte)255; bad[43] = 127; reject(bad);

        WritingAudioLoop loop = new WritingAudioLoop(source);
        short[] quiet = new short[480];
        loop.render(quiet, 240, false, .5f, 1f);
        check(energy(quiet) == 0 && !loop.audible(), "idle silence");
        loop.reset();
        short[] whole = new short[48000 * 2 * 3];
        short[] split = new short[whole.length];
        loop.render(whole, whole.length / 2, true, .5f, .6f);
        WritingAudioLoop other = new WritingAudioLoop(source);
        short[] block = new short[480];
        for (int i = 0; i < split.length; i += block.length) {
            other.render(block, 240, true, .5f, .6f);
            System.arraycopy(block, 0, split, i, block.length);
        }
        check(Arrays.equals(whole, split), "chunk boundaries preserve phase, rate, envelope");
        int maxStep = 0;
        for (int i = 2; i < whole.length; i += 2) {
            maxStep = Math.max(maxStep, Math.abs(whole[i] - whole[i-2]));
            check(whole[i] == whole[i+1], "mono centered");
        }
        check(maxStep < 1500, "smooth synthetic source stays continuous across loop seam");
        loop.render(block, 240, false, .5f, .6f);
        double firstRelease = energy(block);
        loop.render(block, 240, false, .5f, .6f);
        loop.render(block, 240, false, .5f, .6f);
        check(!loop.audible() && firstRelease > 0, "finite release envelope");
        loop.render(block, 240, false, .5f, .6f);
        check(energy(block) == 0, "release ends in exact silence");
        loop.render(block, 240, true, Float.NaN, Float.NaN);
        loop.reset(); loop.render(block, 240, false, 1f, 1f);
        check(energy(block) == 0, "hard stop exact silence");
        short[] slow = new short[48000*2], fast = new short[slow.length];
        new WritingAudioLoop(source).render(slow,48000,true,.6f,.04f);
        new WritingAudioLoop(source).render(fast,48000,true,.6f,1.5f);
        check(energy(fast)>energy(slow)*2,"speed produces a substantial energy difference");
        WritingAudioLoop spatial = new WritingAudioLoop(source);
        spatial.render(slow,48000,true,.6f,.7f,0f);
        check(channelEnergy(slow,0)>channelEnergy(slow,1)*10,"left edge pans left");
        spatial.render(fast,48000,true,.6f,.7f,1f);
        check(channelEnergy(fast,1)>channelEnergy(fast,0)*10,"right edge pans right");
        check(Math.abs(energy(slow)/energy(fast)-1)<.15,"panning approximately preserves power");

        WritingAudioMotion motion = new WritingAudioMotion(-10, 110, 0);
        motion.process(0, 0, 0, 50, 0);
        for (int i=1; i<=100; i++) motion.process(2, (i%2)*.1f, 0, 50, i*10L);
        check(!motion.moving(1000), "sub-dead-zone jitter remains silent");
        motion.process(2, 2, 0, 50, 1010);
        check(motion.moving(1010), "real motion starts without 100 ms gate");
        check(motion.pressure() > .49 && motion.pressure() < .51, "pressure normalization");
        check(!motion.moving(1056), "lost MOVE watchdog stops");
        motion.process(2, 3, 0, 50, 1020);
        check(motion.moving(1020), "continuous stroke remains active");
        motion.process(2, 3.1f, 0, 50, 1030);
        check(!motion.moving(1030), "micro movement stops ongoing sound");
        motion.process(2, 5, 0, 50, 1040);
        motion.process(2, 99, 0, 50, 1030);
        check(motion.eventTime()==1040, "out of order MOVE ignored");
        motion.process(1, 5, 0, 0, 1050);
        check(!motion.moving(1050), "UP stops");
        motion.process(0, 5, 0, 50, 1060);
        motion.process(2, 7, 0, 50, 1070);
        motion.process(3, 7, 0, 50, 1080);
        check(!motion.moving(1080), "CANCEL stops");
        motion.process(0, 5, 0, 50, 1090);
        motion.process(2, Float.NaN, 0, 50, 1100);
        check(!motion.moving(1100), "invalid input stops");
        motion.process(0,0,0,50,2000);
        for(int i=1;i<=100;i++)motion.process(2,i*.1f,0,50,2000+i*10);
        check(!motion.moving(3000),"slow initial contact is silent");
        motion.process(2,12,0,50,3010);
        check(motion.moving(3010),"speeding up a slow stroke resumes without lift");
        motion.process(2,12.1f,0,50,3020);
        check(!motion.moving(3020),"slowing down silences stroke");
        motion.process(2,14,0,50,3030);
        check(motion.moving(3030),"second acceleration resumes without lift");
        if(args.length == 2) offline(Path.of(args[0]), Path.of(args[1]));
        System.out.println("Continuous sample decode, phase, seam, release, dead zone, watchdog: PASS");
    }

    private static void offline(Path file, Path destination) throws Exception {
        WritingAudioSample sample;
        try(InputStream input=Files.newInputStream(file)){sample=WritingAudioSample.read(input);}
        Files.createDirectories(destination);
        // Same 6-second motion schedule: idle, 3 seconds writing, stop, lighter/slower restart, stop.
        for(boolean reference : new boolean[]{true,false}) {
            WritingAudioLoop loop=new WritingAudioLoop(sample);
            short[] audio=new short[48000*6*2];
            short[] block=new short[480];
            for(int frame=0;frame<audio.length/2;frame+=240){
                double t=frame/48000.;
                boolean moving=(t>=.5&&t<3.5)||(t>=4&&t<5.5);
                float pressure=reference?.6f:(t<2?.25f:t<3.5?.8f:.35f);
                float speed=reference?.75f:(t<2?.25f:t<3.5?1.2f:.15f);
                loop.render(block,240,moving,pressure,speed);
                System.arraycopy(block,0,audio,frame*2,block.length);
            }
            writeWav(destination.resolve(reference?"constant-loop.wav":"pressure-speed-loop.wav"),audio);
        }
        System.out.println("Offline previews saved; listening acceptance pending: "+destination);
    }
    private static byte[] wav(int rate,int frames) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        header(out,rate,1,frames*2);
        for(int i=0;i<frames;i++) little(out,(short)(800*Math.sin(i*.02)+250),2);
        return out.toByteArray();
    }
    private static void writeWav(Path file,short[] values)throws IOException{
        try(OutputStream out=new BufferedOutputStream(Files.newOutputStream(file))){header(out,48000,2,values.length*2);for(short value:values)little(out,value,2);}
    }
    private static void header(OutputStream out,int rate,int channels,int bytes)throws IOException{
        out.write("RIFF".getBytes());little(out,36+bytes,4);out.write("WAVEfmt ".getBytes());
        little(out,16,4);little(out,1,2);little(out,channels,2);little(out,rate,4);
        little(out,rate*channels*2,4);little(out,channels*2,2);little(out,16,2);
        out.write("data".getBytes());little(out,bytes,4);
    }
    private static void little(OutputStream out,int v,int n)throws IOException{for(int i=0;i<n;i++)out.write((v>>>(8*i))&255);}
    private static void putInt(byte[] bytes,int p,int value){for(int i=0;i<4;i++)bytes[p+i]=(byte)(value>>>(8*i));}
    private static double energy(short[] a){double e=0;for(short s:a)e+=(double)s*s;return e;}
    private static double channelEnergy(short[] a,int channel){double e=0;for(int i=channel;i<a.length;i+=2)e+=(double)a[i]*a[i];return e;}
    private static void reject(byte[] b)throws Exception{try{WritingAudioSample.read(new ByteArrayInputStream(b));throw new AssertionError("bad WAV accepted");}catch(IOException expected){}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
