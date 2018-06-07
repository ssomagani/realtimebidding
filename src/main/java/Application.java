import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.ClosedShape;
import akka.stream.UniformFanOutShape;
import akka.stream.javadsl.*;
import org.voltdb.client.Client;
import org.voltdb.client.ClientFactory;
import org.voltdb.client.ClientResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;

public class Application {

    public static class Auction {
        public final int publshrId, invenId, impType, bidFloor;
        public Auction(int publshrId, int invenId, int impType, int bidFloor) {
            this.bidFloor = bidFloor;
            this.publshrId = publshrId;
            this.invenId = invenId;
            this.impType = impType;
        }
        public static final Auction BAD_AUCTION = new Auction(-1, -1, -1, -1);
    }

    public static void main(String[] args) throws IOException {
        ActorSystem system = ActorSystem.create("system");
        ActorMaterializer materializer = ActorMaterializer.create(system);

        Client client = ClientFactory.createClient();
        client.createConnection("localhost");

        (new Application()).start(materializer, client);

        system.terminate();
    }

    static final Integer[][] INPUT_MATRIX =         // Attributes of 5 valid and 1 invalid auctions
            {
                    {1, 2, 3, 4, 5, 5},
                    {101, 102, 103, 104, 105, 105},
                    {1, 2, 3, 4, 5, 5},
                    {235, 135, 145, 190, 110, 110}
            };

    private void start(ActorMaterializer materializer, Client client) {

        List<Source<Integer, ?>> sourceList = Arrays.asList(
                Source.from(Arrays.asList(INPUT_MATRIX[0])),
                Source.from(Arrays.asList(INPUT_MATRIX[1])),
                Source.from(Arrays.asList(INPUT_MATRIX[2])),
                Source.from(Arrays.asList(INPUT_MATRIX[3]))
        );

        Source<Auction, NotUsed> auctionSource = Source.<Integer, Auction>zipWithN((ints) -> {
            return new Auction(ints.get(0), ints.get(1), ints.get(2), ints.get(3));
        }, sourceList);


        final Sink<Auction, CompletionStage<Done>> volt_sink = Sink.<Auction> last().foreach(
                (a) -> client.callProcedure("auctions.insert", a.publshrId, a.invenId, a.impType, a.bidFloor)
        );

        Flow<Auction, Auction, NotUsed> volt_flow = Flow.of(Auction.class).map(
                (in) -> {
                    ClientResponse response = client.callProcedure("auctions.insert",
                            in.publshrId, in.invenId, in.impType, in.bidFloor);
                    return (response.getStatus() == 1) ? in : Auction.BAD_AUCTION;
                }
        ).filterNot((outAuction) -> outAuction == Auction.BAD_AUCTION);

        final Sink<Auction, CompletionStage<Done>> bid_sink = Sink.<Auction> last().foreach(
                (a) -> System.out.println("Inviting bids on auction inven " + a.invenId)
        );

        final Sink<Auction, CompletionStage<Integer>> statistics_sink = Sink.<Integer, Auction> fold(0,
                (prev, auction) -> {
                    prev++;
                    if(prev % 5 == 0)
                        System.out.println(prev + " auctions processed");
                    return prev;
                }
        );

        RunnableGraph.fromGraph(GraphDSL.create(bid_sink, (builder, out) -> {
            final UniformFanOutShape<Auction, Auction> bcast = builder.add(Broadcast.create(2));

            builder.from(builder.add(auctionSource)).via(builder.add(volt_flow)).viaFanOut(bcast).to(out);
            builder.from(bcast).to(builder.add(statistics_sink));

            return ClosedShape.getInstance();
        })).run(materializer);
    }
}
