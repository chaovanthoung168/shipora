package com.shipora.worker.adapter.out.docker;

import com.shipora.worker.domain.ExecutionFailure;
import net.schmizz.sshj.connection.channel.direct.Session;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SshDockerTransportTest {
 @Test void waitsForLongRunningRemoteCommandWithoutUsingSshjTimedJoin()throws Exception{
  var command=mock(Session.Command.class);when(command.getExitStatus()).thenReturn(null,null,0);when(command.isOpen()).thenReturn(true);
  SshDockerTransport.awaitExit(command,()->{},Duration.ofSeconds(3));
  verify(command,never()).join(anyLong(),any(TimeUnit.class));assertEquals(0,command.getExitStatus());
 }
 @Test void leaseLossStopsWaitingImmediately(){
  var command=mock(Session.Command.class);when(command.getExitStatus()).thenReturn(null);
  assertThrows(ExecutionFailure.LeaseLost.class,()->SshDockerTransport.awaitExit(command,()->{throw new ExecutionFailure.LeaseLost();},Duration.ofSeconds(3)));
  verify(command,never()).isOpen();
 }
 @Test void closedChannelWithoutExitStatusFailsRatherThanWaitingForever(){
  var command=mock(Session.Command.class);when(command.getExitStatus()).thenReturn(null);when(command.isOpen()).thenReturn(false);
  assertThrows(ExecutionFailure.class,()->SshDockerTransport.awaitExit(command,()->{},Duration.ofSeconds(3)));
 }
 @Test void remoteCommandWaitKeepsItsDeadline(){
  var command=mock(Session.Command.class);when(command.getExitStatus()).thenReturn(null);when(command.isOpen()).thenReturn(true);
  assertThrows(ExecutionFailure.class,()->SshDockerTransport.awaitExit(command,()->{},Duration.ZERO));
 }
}
