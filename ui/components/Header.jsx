import Head from "next/head"

export default ({ title }) => (
  <header>
    <Head>
      <meta httpEquiv="X-UA-Compatible" content="IE=edge"/>
      <meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=no"/>
      <meta name="author" content="Michel Krämer"/>
      <meta name="description" content="Scientific Workflows in the Cloud"/>
      <meta name="robots" content="index,follow"/>
      <link href="https://fonts.googleapis.com/css?family=Roboto:300,400" rel="stylesheet"/>
      <link href="https://fonts.googleapis.com/css?family=Roboto+Condensed:300,400" rel="stylesheet"/>
      <title>{title && title + " « "}Steep Workflow Management System</title>
    </Head>
  </header>
)
